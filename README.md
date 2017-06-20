# A Gossip-Style Failure Detection Service 
This is the project of Distributed Systems 2, Fall Semester 2016-17, University of Trento.

This project implements a Gossip-style Failure Detection Service. It takes inspiration from the paper ["A Gossip-Style Failure Detection Service"](gossip-style-failure-detection-service.pdf) of Robbert van Renesse (it can be found in [docs](docs/) folder).

Authors: [Andrea Zorzi](https://github.com/Andr35) & [Davide Pedranz](https://github.com/davidepedranz) & [Davide Vecchia](https://github.com/davide-vecchia).

## Dependencies
The software is written in Java and uses the [Akka](http://akka.io/) framework.
We use [Gradle](https://gradle.org/) to build, test and run the project.
To build and run the project, you need only a working Java 8 JDK installed on the systems.
For the first build, the needed dependecies are downloaded from the Internet, so make 
sure to have a working Internet connection.

## Build
// TODO complete

Run the following command from the project root:

```bash
./gradlew node
```
This command will generate a JAR archive `build/libs/node.jar` with all the dependencies needed to run the project.

## Run
// TODO describe

You need to provide the following environment variables:

| Variable | Scope                                    | Notes        |
| -------- | ---------------------------------------- | ------------ |
| HOST     | The hostname of the machine where Node or Client is executed. |              |
| PORT     | The port of the Node or the Client.      |              |
| ID  | A unique ID for the new node that will join the system. | `Node` only. |

To run the `Node`, run
```bash
java -jar build/libs/node.jar [COMMAND]
```



## Script

The project is provided with a script to automate the deployment / start / stop steps.

// TODO describe



### Example
The following example shows how to run the some nodes and make some queries.
Please note that some operating system or shell could use a slightly different syntax for environment variables.

// TODO: write some examples

```bash
# bootstrap the system
HOST=127.0.0.1 PORT=20010 NODE_ID=10 java -jar build/libs/node.jar bootstrap

# add a new node
HOST=127.0.0.1 PORT=20020 NODE_ID=20 java -jar build/libs/node.jar join 127.0.0.1 20010
```

### Assertions
The software contains assertions, in order to ensure a correct execution and catch bugs early.
By default, Java disables all assertions. You can decide to enable them with the `-ea`  option for the JVM:
```bash
java -ea -jar build/libs/node.jar [COMMAND]
```

## Test
// TODO remove?
We wrote some [JUnit](http://junit.org) test cases.
You can run the test from the command line using Gradle:

```bash
./gradlew check
```
The command compiles the project and run all test cases. The result is shown in the standard output.
Please note that many test cases will spawn many times multiple Akka actors on your machine,
so the test suite can take some minutes to run.

## License
The source code is licences under the MIT license.
A copy of the license is available in the [LICENSE](LICENSE) file.
