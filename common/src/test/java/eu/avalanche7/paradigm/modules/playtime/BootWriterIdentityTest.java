package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.storage.*;
import eu.avalanche7.paradigm.storage.identity.*;
import eu.avalanche7.paradigm.storage.json.JsonPlayerRepository;
import eu.avalanche7.paradigm.storage.model.*;
import eu.avalanche7.paradigm.storage.mysql.MysqlDialect;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.sql.*;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;
import eu.avalanche7.paradigm.utils.DebugLogger;

class BootWriterIdentityTest {
    @TempDir Path root;
    @Test void jsonClonePendingRecoveryLostAckAndRepeatedBoots() throws Exception { verify("json"); }
    @Test void sqliteClonePendingRecoveryLostAckAndRepeatedBoots() throws Exception { verify("sqlite"); }
    @Test void mariaDbIndependentClonesPendingRecoveryLostAckAndRepeatedBoots() throws Exception {
        assumeTrue("true".equals(System.getenv("PARADIGM_PLAYTIME_MARIADB")));
        verify("mysql");
    }

    @Test void sqliteHardCrashBeforeCommitRecoversJournaledTime() throws Exception {
        crashAndRecover(false);
    }

    @Test void sqliteHardCrashAfterCommitDoesNotReplayAnAcknowledgedWatermarkTwice() throws Exception {
        crashAndRecover(true);
    }

    private void crashAndRecover(boolean commit) throws Exception {
        var directory = Files.createDirectory(root.resolve(commit ? "after-commit" : "before-commit"));
        var log = directory.resolve("child.log");
        String uuid = UUID.randomUUID().toString();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", testClasspath(), CrashProbe.class.getName(),
                directory.toString(), uuid, Boolean.toString(commit))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child did not reach the fault");
            assertEquals(23, process.exitValue(), () -> {
                try { return Files.readString(log); }
                catch (java.io.IOException failure) { return failure.toString(); }
            });
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }

        List<SqlConnectionProvider> providers = new ArrayList<>();
        var path = directory.resolve("journal.json");
        try {
            var repo = repository("sqlite", directory, "gtnh", providers);
            assertEquals(commit ? 5000L : 0L, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
            for (int boot = 0; boot < 3; boot++) {
                try (var journal = new PlaytimeJournal(path, "crash-test", () -> repo, "gtnh")) {
                    var service = new PlaytimeService(null, journal, () -> repo,
                            action -> CompletableFuture.completedFuture(action.get()), () -> 1000L);
                    service.start();
                    assertEquals("available", service.recoveryStatus(null));
                    var state = repo.readPlaytime(uuid);
                    assertEquals(5000L, state.profile().playtimeMs());
                    assertEquals(5000L, state.localPlaytimeMs("gtnh"));
                    assertEquals(List.of(5000L), List.copyOf(state.playtimeStreams().values()));
                    assertTrue(journal.open().streams.isEmpty());
                    service.stop();
                }
            }
        } finally {
            providers.forEach(SqlConnectionProvider::close);
        }
    }

    private static String testClasspath() throws Exception {
        var paths = new LinkedHashSet<String>();
        for (ClassLoader loader = BootWriterIdentityTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) paths.add(Path.of(url.toURI()).toString());
                }
            }
        }
        paths.add(System.getProperty("java.class.path"));
        return String.join(java.io.File.pathSeparator, paths);
    }

    public static final class CrashProbe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            String uuid = args[1];
            boolean commit = Boolean.parseBoolean(args[2]);
            var providers = new ArrayList<SqlConnectionProvider>();
            var repo = new BootWriterIdentityTest().repository("sqlite", directory, "gtnh", providers);
            try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "crash-test", () -> repo, "gtnh")) {
                var faulted = (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),
                        new Class<?>[]{PlayerRepository.class}, (proxy, method, arguments) -> {
                            boolean write = method.getName().equals("recordPlaytime");
                            if (write && !commit) Runtime.getRuntime().halt(23);
                            Object result;
                            try { result = method.invoke(repo, arguments); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                            if (write) Runtime.getRuntime().halt(23);
                            return result;
                        });
                var player = (IPlayer) Proxy.newProxyInstance(IPlayer.class.getClassLoader(),
                        new Class<?>[]{IPlayer.class}, (proxy, method, arguments) -> switch (method.getName()) {
                            case "getUUID" -> uuid;
                            case "getName" -> "CrashProbe";
                            default -> null;
                        });
                var clock = new AtomicLong(1000L);
                var service = new PlaytimeService(null, journal, () -> faulted,
                        action -> CompletableFuture.completedFuture(action.get()), clock::get);
                service.start();
                service.beginSession(player);
                clock.set(6000L);
                service.endSession(player);
                throw new AssertionError("did not interrupt the write");
            } finally {
                providers.forEach(SqlConnectionProvider::close);
            }
        }
    }

    void verify(String backend) throws Exception {
        Path directory=Files.createTempDirectory(root,backend);
        var providers=new ArrayList<SqlConnectionProvider>();
        var workers=new ArrayList<ExecutorService>();
        try {
            PlayerRepository a=repository(backend,directory,"a",providers);
            PlayerRepository b=backend.equals("json")?a:repository(backend,directory,"b",providers);
            String uuid=UUID.randomUUID().toString();
            IPlayer player=(IPlayer)Proxy.newProxyInstance(IPlayer.class.getClassLoader(),new Class<?>[]{IPlayer.class},(p,m,args)->switch(m.getName()){case "getUUID"->uuid;case "getName"->"Clone";default->null;});
            String oldWriter=UUID.randomUUID().toString();
            a.recordPlaytime(new StoredPlayerProfile(uuid,"Historical",100,200,0),oldWriter,3000);
            Path source=directory.resolve("source/journal.json");Files.createDirectories(source.getParent());
            Files.writeString(source,"{\"writerId\":\""+oldWriter+"\",\"storageIdentity\":\"shared\",\"players\":{\""+uuid+"\":{\"name\":\"Historical\",\"firstSeenMs\":100,\"lastSeenMs\":200,\"cumulativeMs\":5000}}}");
            Path pa=directory.resolve("a/journal.json"),pb=directory.resolve("b/journal.json");
            Files.createDirectories(pa.getParent());Files.createDirectories(pb.getParent());
            Files.copy(source,pa);Files.copy(source,pb);
            AtomicLong ta=new AtomicLong(1000),tb=new AtomicLong(1000);
            AtomicBoolean loseAck=new AtomicBoolean();
            PlayerRepository faulted=(PlayerRepository)Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),new Class<?>[]{PlayerRepository.class},(p,m,args)->{
                Object result;try{result=m.invoke(a,args);}catch(InvocationTargetException e){throw e.getCause();}
                if(m.getName().equals("recordPlaytime")&&loseAck.getAndSet(false))throw new StorageException("committed but acknowledgement lost");return result;
            });
            String wa,wb;
            try(var ja=new PlaytimeJournal(pa,"shared",null,a.playtimeServerId());var jb=new PlaytimeJournal(pb,"shared",null,b.playtimeServerId())) {
                var ea=Executors.newSingleThreadExecutor();var eb=Executors.newSingleThreadExecutor();workers.add(ea);workers.add(eb);
                var sa=new PlaytimeService(null,ja,()->faulted,work->CompletableFuture.supplyAsync(work,ea),ta::get);
                var sb=new PlaytimeService(null,jb,()->b,work->CompletableFuture.supplyAsync(work,eb),tb::get);
                sa.beginSession(player);sb.beginSession(player);sa.flushAllBlocking();sb.flushAllBlocking();
                assertEquals(5000,a.getProfile(uuid).orElseThrow().playtimeMs());
                wa=ja.open().writerId;wb=jb.open().writerId;
                assertNotEquals(oldWriter,wa);assertNotEquals(oldWriter,wb);assertNotEquals(wa,wb);
                ta.set(6000);tb.set(8000);loseAck.set(true);
                var barrier=new CyclicBarrier(2);var callers=Executors.newFixedThreadPool(2);
                try {
                    var fa=callers.submit(()->{barrier.await();sa.flushAllBlocking();return null;});
                    var fb=callers.submit(()->{barrier.await();sb.flushAllBlocking();return null;});
                    fa.get(15,TimeUnit.SECONDS);fb.get(15,TimeUnit.SECONDS);
                }finally{callers.shutdownNow();}
                assertEquals(17000,a.getProfile(uuid).orElseThrow().playtimeMs());
                sa.flushAllBlocking();sb.flushAllBlocking();
                assertEquals(17000,a.getProfile(uuid).orElseThrow().playtimeMs());
                assertEquals(Map.of(oldWriter,5000L,wa,5000L,wb,7000L),a.getProfileState(uuid).playtimeStreams());
                assertFalse(ja.open().streams.containsKey(oldWriter));
            }
            for(int restart=0;restart<4;restart++) {
                try(var journal=new PlaytimeJournal(pa,"shared",null,a.playtimeServerId())) {
                    var worker=Executors.newSingleThreadExecutor();workers.add(worker);
                    var service=new PlaytimeService(null,journal,()->a,work->CompletableFuture.supplyAsync(work,worker),ta::get);
                    service.recoverJournal();worker.submit(()->{}).get(10,TimeUnit.SECONDS);
                    service.flushAllBlocking();
                    assertEquals(17000,a.getProfile(uuid).orElseThrow().playtimeMs());
                    assertEquals(3,a.getProfileState(uuid).playtimeStreams().size());
                    service.beginSession(player);service.flushAllBlocking();
                    assertEquals(backend.equals("json") ? 12000 : 5000,service.onlinePlaytimeMs(player));
                    service.endSession(player);service.flushAllBlocking();
                    assertEquals(3,a.getProfileState(uuid).playtimeStreams().size());
                }
            }
            System.out.println("BOOT_WRITER_VERIFIED backend="+backend+" historical=5000 new_a=5000 new_b=7000 total=17000 streams=3 restarts=4");
        }finally{
            for(var worker:workers){worker.shutdown();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
            for(var provider:providers)provider.close();
        }
    }
    PlayerRepository repository(String backend,Path directory,String server,List<SqlConnectionProvider> providers) {
        if(backend.equals("json"))return new JsonPlayerRepository(new PlayerDataStore(LoggerFactory.getLogger(getClass()),new DebugLogger(null),new IConfig(){public Path getConfigDirectory(){return directory.resolve("profiles");}public String getModId(){return "paradigm";}}));
        var config=new StorageConfig();config.runtimeLibraries.enabled=false;config.provider=backend;config.serverId=server;
        config.sqlite.path=directory.resolve("profiles.db").toString();
        config.sql.host="127.0.0.1";config.sql.port=33527;config.sql.database="paradigm_playtime_test";config.sql.username="playtime_test";config.sql.password="disposable-playtime";
        var provider=new SqlConnectionProvider(config,backend.equals("mysql")?new MysqlDialect():new SqliteDialect(),null);providers.add(provider);
        new SqlMigrationRunner(provider,null).runAvailableMigrations();
        return new SqlPlayerRepository(new SqlExecutor(provider),new StorageContext(new ServerIdentityService(null,config).current()));
    }
}
