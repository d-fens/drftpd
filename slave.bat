set VMARGS=-Djava.library.path=. -Djava.rmi.dgc.leaseValue=300000 -Djava.rmi.server.randomIDs=true
java %VMARGS% net.sf.drftpd.slave.SlaveImpl drftpd.conf
pause
