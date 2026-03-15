#!/usr/bin/env fish

set script_dir (cd (dirname (status --current-filename)); pwd)
exec python3 "$script_dir/release_all_local.py" $argv

