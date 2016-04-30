# mini-web-server
Use maven commands to clean, build and package as jar.
```sh
$ mvn clean package
```
To run:
```sh
java -cp < path of HTTPServer-1.0-SNAPSHOT.jar > com.anant.app.Reactor < port > < root folder path >
```

Uses code from following sites:

 1. http://blog.genuine.com/2013/07/nio-based-reactor/
 2. http://www.jibble.org/miniwebserver/
