#!/usr/bin/env bash
# Build the GroundTruth Paper plugin.
#   SPIGOT_API=/path/to/spigot-api.jar ./build.sh
set -e
cd "$(dirname "$0")"
CP="${SPIGOT_API:-libs/spigot-api.jar}"
rm -rf build/classes && mkdir -p build/classes
javac -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file build/GroundTruth.jar -C build/classes .
echo "built build/GroundTruth.jar"
