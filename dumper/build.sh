#!/usr/bin/env bash
# Build the GroundTruth offline dumper (fat jar with sqlite-jdbc bundled).
#   SQLITE_JDBC=/path/to/sqlite-jdbc.jar ./build.sh
set -e
cd "$(dirname "$0")"
JDBC="${SQLITE_JDBC:-libs/sqlite-jdbc.jar}"
rm -rf build/classes && mkdir -p build/classes
javac -cp "$JDBC" -d build/classes *.java
( cd build/classes && unzip -oq "$JDBC" -x 'META-INF/*.SF' 'META-INF/*.DSA' 'META-INF/*.RSA' 'META-INF/MANIFEST.MF' )
jar --create --file build/GroundTruthDumper.jar \
  --main-class club.footlickers.groundtruth.dumper.Dumper -C build/classes .
echo "built build/GroundTruthDumper.jar"
