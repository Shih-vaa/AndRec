#!/system/bin/sh
# Spike 2 helper: create synthetic 1 KiB files ON the phone (runs under adb shell).
# Usage: gen_docs.sh <first_dir_index> <dir_count>
# Each directory d_<n> holds 400 files plus a sub-directory "s" with 100 more (500 files per directory, depth 2).
ROOT=/sdcard/Documents/SpikeSaf
d=$1
end=$(($1 + $2))
while [ "$d" -lt "$end" ]; do
  mkdir -p "$ROOT/d_$d/s"
  i=0
  while [ "$i" -lt 400 ]; do
    head -c 1024 /dev/urandom > "$ROOT/d_$d/f_$i.txt"
    i=$((i + 1))
  done
  i=0
  while [ "$i" -lt 100 ]; do
    head -c 1024 /dev/urandom > "$ROOT/d_$d/s/g_$i.txt"
    i=$((i + 1))
  done
  d=$((d + 1))
done
echo done
