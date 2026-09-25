# Java 25 for this project only.  Usage:  source env.sh
#
# The global JAVA_HOME in ~/.bashrc stays pinned to JDK 17 on purpose:
# React Native's CMake tasks break on JDK 24+. Nothing here touches that.
# Override the location with:  JDK25=/path/to/jdk source env.sh

JDK25="${JDK25:-$HOME/.jdks/jdk-25.0.4.1+1}"

if [ -x "$JDK25/bin/javac" ]; then
    export JAVA_HOME="$JDK25"
    export PATH="$JAVA_HOME/bin:$PATH"
    java -version
else
    echo "env.sh: no JDK 25 found at $JDK25" >&2
    echo "  install one (https://adoptium.net/temurin/releases/?version=25)" >&2
    echo "  then:  JDK25=/path/to/jdk-25 source env.sh" >&2
fi
