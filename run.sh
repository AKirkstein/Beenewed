#!/bin/sh
# Compile and run Beenewed from source (Java 17+). Works even if the folder name has spaces.
cd "$(dirname "$0")" || exit 1
rm -rf out && mkdir out
javac -d out src/beenewed/*.java src/beenewed/model/*.java src/beenewed/ui/*.java src/beenewed/screens/*.java src/beenewed/dialogs/*.java && java -cp out beenewed.App
