#! /bin/bash

#
# Skeleton bash script suitable for starting and stopping 
# wrapped Java apps on the Linux platform.
#

#-----------------------------------------------------------------------------
# These settings can be modified to fit the needs of your application

# Application
APP_NAME="DrFTPD Slave"
APP_LONG_NAME="Distributed FTP Daemon Slave"

# Wrapper
WRAPPER_CMD="bin/wrapper"
WRAPPER_CONF="conf/wrapper.conf"

# Priority at which to run the wrapper.  See "man nice" for valid priorities.
#  nice is only used if a priority is specified.
PRIORITY=

# Do not modify anything beyond this point
#-----------------------------------------------------------------------------

echo "--------------------------------------------------------------------"
echo "The Java Service Wrapper bash script has been deprecated and will be"
echo "removed in a future version.  Please migrate to using the sh script."
echo "--------------------------------------------------------------------"
echo ""

# Get the fully qualified path to the script
case $0 in
    /*)
        SCRIPT="$0"
        ;;
    *)
        PWD=`pwd`
        SCRIPT="$PWD/$0"
        ;;
esac

# Change spaces to ":" so the tokens can be parsed.
SCRIPT=`echo $SCRIPT | sed -e 's; ;:;g'`
# Get the real path to this script, resolving any symbolic links
TOKENS=`echo $SCRIPT | sed -e 's;/; ;g'`
REALPATH=
for C in $TOKENS; do
    REALPATH="$REALPATH/$C"
    while [ -h "$REALPATH" ] ; do
        LS="`ls -ld "$REALPATH"`"
        LINK="`expr "$LS" : '.*-> \(.*\)$'`"
        if expr "$LINK" : '/.*' > /dev/null; then
            REALPATH="$LINK"
        else
            REALPATH="`dirname "$REALPATH"`""/$LINK"
        fi
    done
done
# Change ":" chars back to spaces.
REALPATH=`echo $REALPATH | sed -e 's;:; ;g'`

# Change the current directory to the location of the script
cd "`dirname "$REALPATH"`"

# Find pidof.
PIDOF="/bin/pidof"
if [ ! -x $PIDOF ]
then
    PIDOF="/sbin/pidof"
    if [ ! -x $PIDOF ]
    then
        echo "Cannot find 'pidof' in /bin or /sbin."
        echo "This script requires 'pidof' to run."
        exit 1
    fi
fi

# Build the nice clause
if [ "X$PRIORITY" = "X" ]
then
    CMDNICE=""
else
    CMDNICE="nice -$PRIORITY"
fi

console() {
    echo "Running $APP_LONG_NAME..."
    pid=`$PIDOF $APP_NAME`
    if [ -z $pid ]
    then
        exec -a $APP_NAME $CMDNICE $WRAPPER_CMD $WRAPPER_CONF
    else
        echo "$APP_LONG_NAME is already running."
        exit 1
    fi
}

start() {
    echo "Starting $APP_LONG_NAME..."
    pid=`$PIDOF $APP_NAME`
    if [ -z $pid ]
    then
        exec -a $APP_NAME $CMDNICE $WRAPPER_CMD $WRAPPER_CONF wrapper.daemonize=TRUE
    else
        echo "$APP_LONG_NAME is already running."
        exit 1
    fi
}

stopit() {
    echo "Stopping $APP_LONG_NAME..."
    pid=`$PIDOF $APP_NAME`
    if [ -z $pid ]
    then
        echo "$APP_LONG_NAME was not running."
    else
        # Running so try to stop it.
        kill $pid
        if [ $? -ne 0 ]
        then
            # An explanation for the failure should have been given
            echo "Unable to stop $APP_LONG_NAME."
            exit 1
        fi

        # We can not predict how long it will take for the wrapper to
        #  actually stop as it depends on settings in wrapper.conf.
        #  Loop until it does.
        CNT=0
        TOTCNT=0
        while [ ! -z $pid ]
        do
            # Loop for up to 5 minutes
            if [ "$TOTCNT" -lt "300" ]
            then
                if [ "$CNT" -lt "5" ]
                then
                    CNT=`expr $CNT + 1`
                else
                    echo "Waiting for $APP_LONG_NAME to exit..."
                    CNT=0
                fi
                TOTCNT=`expr $TOTCNT + 1`

                sleep 1

                pid=`$PIDOF $APP_NAME`
            else
                pid=
            fi
        done

        pid=`$PIDOF $APP_NAME`
        if [ ! -z $pid ]
        then
            echo "Timed out waiting for $APP_LONG_NAME to exit."
            echo "  Attempting a forced exit..."
            kill -9 $pid
        fi

        pid=`$PIDOF $APP_NAME`
        if [ ! -z $pid ]
        then
            echo "Failed to stop $APP_LONG_NAME."
            exit 1
        else
            echo "Stopped $APP_LONG_NAME."
        fi
    fi
}

dump() {
    echo "Dumping $APP_LONG_NAME..."
    pid=`$PIDOF $APP_NAME`
    if [ -z $pid ]
    then
        echo "$APP_LONG_NAME was not running."
    else
        kill -3 $pid

        if [ $? -ne 0 ]
        then
            echo "Failed to dump $APP_LONG_NAME."
        else
            echo "Dumped $APP_LONG_NAME."
        fi
    fi
}

case "$1" in

    'console')
        console
        ;;

    'start')
        start
        ;;

    'stop')
        stopit
        ;;

    'restart')
        stopit
        start
        ;;

    'dump')
        dump
        ;;

    *)
        echo "Usage: $0 { console | start | stop | restart | dump }"
        exit 1
        ;;
esac

exit 0