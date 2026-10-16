Emporia marketplace server
==========================

This directory holds an Emporia server installed by emporia-<version>.run. The server runs as the
systemd service "emporia", as the system user "emporia", with this directory as its working directory.

Files
-----
  emporia.jar      the server (replaced on an upgrade)
  WebContent/      the web pages (replaced on an upgrade)
  emporia.json     the configuration: ports, database, certificate (kept on an upgrade)
  emporia.env      Java options, JAVA_OPTS (kept on an upgrade)
  cert.pem         the HTTPS certificate (PEM)
  key.pem          its private key (PEM; owner root, group emporia, mode 640)
  emporia.db       the SQLite database, with emporia.db-wal and emporia.db-shm
  server.log       the server's log
  log4j2.xml       the logging configuration
  uninstall.sh     removes the installation

The service
-----------
All commands as root (or with sudo).

  systemctl start emporia       start the server
  systemctl stop emporia        stop the server (it stores any queued trades first)
  systemctl restart emporia     stop and start, e.g. after a change of emporia.json or emporia.env
  systemctl status emporia      is it running, and its latest messages

  systemctl enable emporia      start the server when the machine boots (the installer did this)
  systemctl disable emporia     do not start it at boot
  systemctl enable --now emporia    enable and start in one step

The service is restarted automatically if the server fails.

Logs
----
  tail -f server.log            everything the server logs
  journalctl -u emporia         warnings and errors, and the service's start and stop

After a start
-------------
  - The trading interface is at https://<host>:<https port>/ and the admin interface at /admin.
    The first admin login is Admin / changeit; change the password at once.
  - The server starts with every market CLOSED. Open them in the admin interface (Markets).
  - The port may have to be opened in the firewall, e.g.
      firewall-cmd --permanent --add-port=8443/tcp && firewall-cmd --reload

Changing the settings
---------------------
  Ports:        https_port and http_port (remove it for HTTPS only) in emporia.json
  Certificate:  replace cert.pem and key.pem (keep the key's group emporia and mode 640)
  Memory:       JAVA_OPTS in emporia.env, e.g. JAVA_OPTS="-Xms256m -Xmx2g"
  MongoDB:      to use MongoDB instead of SQLite, set in emporia.json
                  "database": {"type": "mongodb", "host": "127.0.0.1", "port": 27017, "name": "emporia"}
                (the data in emporia.db is not moved to MongoDB)
Then run: systemctl restart emporia

Backup
------
Stop the server and copy emporia.db (with emporia.db-wal if present), or, while it runs (with the
sqlite3 tool installed):
  sqlite3 emporia.db ".backup /path/to/emporia-backup.db"

Upgrade
-------
Run the new installer and give this directory: sudo bash emporia-<version>.run
It stops the service, replaces emporia.jar and WebContent, keeps the configuration, the database and
the certificate, and starts the service again.

Uninstall
---------
  sudo ./uninstall.sh           removes the service and the program files, keeps the data
  sudo ./uninstall.sh --purge   removes this directory with all its data, and the user emporia
