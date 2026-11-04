#!/usr/bin/env sh
# Fetches verified dependencies into lib/, compiles CircularSync and its test
# suite into out/, and runs the tests.
# Uses $JAVA_HOME/bin when JAVA_HOME is set, otherwise javac/java from PATH.
set -e
cd "$(dirname "$0")"

if [ -n "$JAVA_HOME" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=javac
  JAVA=java
fi

# Windows JVMs (Git Bash, MSYS, Cygwin) need ';' as the classpath separator.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

"$JAVA" tools/FetchLibs.java lib

rm -rf out
mkdir -p out
find src test -name '*.java' > out/sources.txt
"$JAVAC" --release 17 -Xlint:all -encoding UTF-8 -cp "lib/*" -d out @out/sources.txt
echo "Compiled $(wc -l < out/sources.txt | tr -d ' ') source files into out/"

if [ "$1" != "--skip-tests" ]; then
  "$JAVA" -cp "out${SEP}lib/*" com.circularsync.test.CircularSyncTestSuite
fi
