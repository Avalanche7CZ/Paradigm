package eu.avalanche7.paradigm.platform;

public interface InventoryConsumption {
    void consume();
    void restore();
    default InventoryConsumption guarded(java.util.function.BooleanSupplier authorizedThreadAndSession) {
        InventoryConsumption delegate = this;
        return new InventoryConsumption() {
            private boolean started;
            @Override public void consume() {
                if (!authorizedThreadAndSession.getAsBoolean()) throw new IllegalStateException("Inventory thread or player session changed");
                started = true; delegate.consume();
            }
            @Override public void restore() {
                if (!started) return;
                if (!authorizedThreadAndSession.getAsBoolean()) throw new IllegalStateException("Inventory rollback requires the original server thread and player session");
                delegate.restore();
            }
        };
    }
}
