#!/system/bin/sh
# Spike 1 helper: create synthetic media files ON the phone (runs under adb shell).
# Usage: gen_synthetic.sh <start_index> <jpg_count> <mp4_count>
# File names are SYN_<index>, so several copies with disjoint ranges can run in parallel.
# Files hold random bytes (unique content, so hashing is real) behind a JPEG header/footer for .jpg.
# Everything lands in a folder named SpikeSynthetic under Pictures and Movies so it can be removed cleanly.
IMG=/sdcard/Pictures/SpikeSynthetic
VID=/sdcard/Movies/SpikeSynthetic
mkdir -p "$IMG" "$VID"

i=$1
end=$(($1 + $2))
while [ "$i" -lt "$end" ]; do
  kb=$((50 + RANDOM % 450))
  { printf '\377\330\377\340'; head -c $((kb * 1024)) /dev/urandom; printf '\377\331'; } > "$IMG/SYN_$i.jpg"
  i=$((i + 1))
done

i=$1
end=$(($1 + $3))
while [ "$i" -lt "$end" ]; do
  kb=$((1024 + RANDOM % 6144))
  head -c $((kb * 1024)) /dev/urandom > "$VID/SYN_$i.mp4"
  i=$((i + 1))
done
echo done
