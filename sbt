#!/bin/sh
java -Xmx2048m -XX:MaxPermSize=256m -jar `dirname $0`/project/sbt-launch-0.7.5.RC0.jar "$@"
exit $?
