#!/bin/sh
# Сборка без интернета: компилируем против заглушек Bukkit (stubs/), в jar они не попадают.
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/stubs build/classes
javac --release 21 -d build/stubs $(find stubs -name '*.java')
javac --release 21 -encoding UTF-8 -cp build/stubs -d build/classes $(find src/main/java -name '*.java')
cp src/main/resources/* build/classes/
jar --create --file ../ClaudeBridge.jar -C build/classes .
echo "OK: ../ClaudeBridge.jar"
