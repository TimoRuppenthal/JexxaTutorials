# TimeService - Async Messaging

## What You Learn

*   [How to write an application core providing some use cases](#1-implement-the-application-core) 
*   [How to implement required driven adapter](#2-implement-the-driven-adapter)
*   [How to implement required driving adapter](#3-implement-drivingadapter)
*   [How to implement the application using application specific driving and driven adapter](#4-implement-the-application)

## What you need

*   Understand tutorial `HelloJexxa` because we explain only new aspects 
*   60 minutes
*   JDK 17  (or higher) installed 
*   Maven 3.6 (or higher) installed
*   A running ActiveMQ instance (at least if you start the application with infrastructure)
*   curl to trigger the application  

## 1. Implement the Application Core

The application core consists of following classes:

*   [`TimeApplicationService:`](src/main/java/io/jexxa/tutorials/timeservice/applicationservice/TimeApplicationService.java) Is an `ApplicationService` in terms of DDD and provides the use cases for a specific client.
*   [`TimePublisher:`](src/main/java/io/jexxa/tutorials/timeservice/domainservice/TimePublisher.java) Only declares a method to publish current time, so that implementation can be realized within the infrastructure.
*   [`MessageDisplay:`](src/main/java/io/jexxa/tutorials/timeservice/domainservice/MessageDisplay.java) Only declares a method to show a message, so that implementation can be realized within the infrastructure.     

### Declare interfaces for the two infrastructure services

The most important aspect here is that a technology-agnostic application must not depend on any technology-stack. Therefore,
we use dependency inversion principle which is explained in more detail [here](README-FlowOfControl.md). 
Interface `TimePublisher` allows to publish the time by an arbitrary technology stack.

```java
public interface TimePublisher
{
    void publish(LocalTime localTime);
}
```                 

The second interface `MessageDisplay` provides the possibility to display some messages. 

```java
public interface MessageDisplay
{
    void show(String message);
}
```      
  
### Implement class `TimeApplicationService`

Now, we can implement our `ApplicationService` that provides two very simple use cases: 
*   Publish current time   
*   Receive and display a published time

Since Jexxa only supports implicit constructor injection, we have to declare all required interfaces in the constructor.    

```java
public class TimeApplicationService
{
    private final TimePublisher timePublisher;
    private final MessageDisplay messageDisplay;

    /**
     * Note: Jexxa supports only implicit constructor injection. Therefore, we must
     * declare all required interfaces in the constructor.
     *
     * @param timePublisher required outbound port for this application service
     * @param messageDisplay required outbound port for this application service
     */
    public TimeApplicationService(TimePublisher timePublisher, MessageDisplay messageDisplay)
    {
        this.timePublisher = Objects.requireNonNull(timePublisher);
        this.messageDisplay = Objects.requireNonNull(messageDisplay);
    }

    /**
     * Implement use case 1: publish current time 
     */
    public void publishTime()
    {
        timePublisher.publish(LocalTime.now());
    }


    /**
     * Implement use case 2 : Shows the previously published time.
     * @param localTime the previously published time
     */
    public void displayPublishedTime(LocalTime localTime)
    {
        var messageWithPublishedTime = "New Time was published, time: " + localTime.format(DateTimeFormatter.ISO_TIME);
        messageDisplay.show(messageWithPublishedTime);
    }
}
```                  

## 2. Implement the Driven Adapter

### Driven Adapter for console output
The interface [`MessageDisplay`](src/main/java/io/jexxa/tutorials/timeservice/domainservice/MessageDisplay.java) is 
implemented by [`MessageDisplayImpl`](src/main/java/io/jexxa/tutorials/timeservice/infrastructure/drivenadapter/display/MessageDisplayImpl.java) 
by just logging given arguments.  

Jexxa uses implicit constructor injection together with a strict convention over configuration approach. Therefore, 
each driven adapter needs one of the following constructors: 

*   A public default constructor `MessageDisplay()`
*   A static factory method `public static MessageDisplay create()`
*   A public constructor with a single `Properties` attribute `MessageDisplay(Properties properties)`
*   A static factory method with a single `Properties` parameter `public static MessageDisplay create(Properties properties)`
   
Since our driven adapter does not need/support any configuration parameter, we can use default constructor generated by Java.

```java
public class MessageDisplayImpl implements MessageDisplay
{
    @Override
    public void show(String message)
    {
        JexxaLogger.getLogger(MessageDisplay.class).info(message);
    }
}
```

### Driven Adapter for messaging ###

Jexxa provides so called `DrivenAdapterStrategy` for various Java-APIs such as JMS. When using these strategies the 
implementation of a driven adapter is just a facade and maps domain specific methods to the technology stack. As you 
can see in the following code, the application specific driven adapter requests the strategy from a so-called strategy 
manager.    

```java
public class TimePublisherImpl implements TimePublisher
{
    public static final String TIME_TOPIC = "TimeService";

    private final MessageSender messageSender;

    // `getMessageSender()` requires a Properties object including all required config information. Therefore, we must 
    // declare a constructor expecting `Properties`, so that Jexxa can hand in all defined properties (e.g., from `jexxa-application.properties`).
    public TimePublisherImpl(Properties properties)
    {
        //Request a message sender for the implemented interface TimePublisher 
        this.messageSender = getMessageSender(TimePublisher.class, properties);
    }

    @Override
    public void publish(LocalTime localTime)
    {
        // For most integrated standard APIs, Jexxa provides a fluent API to improve readability
        // and to emphasize the purpose of the code
        messageSender
                .send(localTime)
                .toTopic(TIME_TOPIC)
                .addHeader("Type", localTime.getClass().getSimpleName())
                .asJson();
    }
}
```

In order to configure your application for a specific message broker, we define all required information in [`jexxa-application.properties`](src/main/resources/jexxa-application.properties): 

```properties
#suppress inspection "UnusedProperty" for whole file
#Settings for JMSAdapter and JMSSender
java.naming.factory.initial=org.apache.activemq.artemis.jndi.ActiveMQInitialContextFactory
java.naming.provider.url=tcp://localhost:61616
java.naming.user=admin
java.naming.password=admin
```                       

## 3. Implement DrivingAdapter
Now, we have to implement the driving adapter `TimeListener` which receives published time information.  

### Implement TimeListener
When receiving asynchronous messages we have to:
1.  Know and declare the object from our application core processing received data
2.  Convert received data into business data
3.  Define the connection information how to receive the data
4.  Forward it to a specific method within the application core. 
 
Implementing a port adapter for JMS using Jexxa is quite easy.

```java
/**
 * 1. Within the constructor we define our class from the application core that will be called. Jexxa automatically
 * injects this object when creating the port adapter. By convention, this is the only object defined in the
 * constructor.
 * <p>
 * 2. In case of JMS we have to implement the JMS specific `MessageListener` interface. To facilitate this, Jexxa offers
 * convenience classes such as TypedMessageListener which perform JSON deserialization into a defined type.
 * <p>
 * 3. The JMS specific connection information is defined as annotation at the onMessage method. 
 * <p>
 * 4. Finally, the implementation of this method just forwards received data to the application service.
 */
public final class TimeListener extends TypedMessageListener<LocalTime> {
    private final TimeApplicationService timeApplicationService;
    private static final String TIME_TOPIC = "TimeService";

    public TimeListener(TimeApplicationService timeApplicationService) {
        super(LocalTime.class);
        this.timeApplicationService = timeApplicationService;
    }

    @Override
    // The JMS specific configuration is defined via annotation.
    @JMSConfiguration(destination = TIME_TOPIC,  messagingType = TOPIC, sharedSubscriptionName = "TimeService", durable = NON_DURABLE)
    public void onMessage(LocalTime localTime) {
        // Forward this information to corresponding application service.
        timeApplicationService.displayPublishedTime(localTime);
    }
}
```

## 4. Implement the Application ##

Finally, we have to write our application. As you can see in the code below, the only difference compared to `HelloJexxa`
is that we bind a JMSAdapter to our TimeListener.    
   
```java
public final class TimeService
{
    public static void main(String[] args)
    {
        //Create your jexxaMain for this application
        var jexxaMain = new JexxaMain(TimeService.class);

        jexxaMain
                // Bind RESTfulRPCAdapter and JMXAdapter to TimeService class so that we can invoke its method
                .bind(RESTfulRPCAdapter.class).to(TimeApplicationService.class)
                .bind(RESTfulRPCAdapter.class).to(jexxaMain.getBoundedContext())
                
                // Bind the JMSAdapter to our 
                .bind(JMSAdapter.class).to(TimeListener.class)

                .run();
    }
}
```  

That's it. 

## Run the Application with console output ##

Disabling of all infrastructure components can be done by property files. By convention, Jexxa tries to find a real implementation of infrastructure components such as a database or messaging system. If they are not configured, Jexxa falls back to dummy implementation that are suitable for local testing.    

```console                                                          
mvn clean install
java -jar "-Dio.jexxa.config.import=./src/test/resources/jexxa-local.properties" ./target/timeservice-jar-with-dependencies.jar
```
You will see following (or similar) output
```console
[main] INFO io.jexxa.utils.JexxaBanner - Config Information: 
[main] INFO io.jexxa.utils.JexxaBanner - Jexxa Version                  : VersionInfo[version=5.0.1-SNAPSHOT, repository=scm:git:https://github.com/jexxa-projects/Jexxa.git/jexxa-core, projectName=Jexxa-Core, buildTimestamp=2022-06-24 05:10]
[main] INFO io.jexxa.utils.JexxaBanner - Context Version                : VersionInfo[version=1.0.20-SNAPSHOT, repository=scm:git:https://github.com/jexxa-projects/JexxaTutorials.git/timeservice, projectName=TimeService, buildTimestamp=2022-06-24 16:53]
[main] INFO io.jexxa.utils.JexxaBanner - Used Driving Adapter           : [JMSAdapter, RESTfulRPCAdapter]
[main] INFO io.jexxa.utils.JexxaBanner - Used Properties Files          : [/jexxa-application.properties, ./src/test/resources/jexxa-local.properties]
[main] INFO io.jexxa.utils.JexxaBanner - Used Message Sender Strategie  : [MessageLogger]
[main] INFO io.jexxa.utils.JexxaBanner - 
[main] INFO io.jexxa.utils.JexxaBanner - Access Information: 
[main] INFO io.jexxa.utils.JexxaBanner - Listening on: http://0.0.0.0:7502
[main] INFO io.jexxa.utils.JexxaBanner - OpenAPI available at: http://0.0.0.0:7502/swagger-docs
[main] INFO io.jexxa.utils.JexxaBanner - JMS Listening on  : tcp://ActiveMQ:61616
[main] INFO io.jexxa.utils.JexxaBanner -    * JMS-Topics   : []
[main] INFO io.jexxa.utils.JexxaBanner -    * JMS-Queues   : []
[main] INFO io.jexxa.core.JexxaMain - BoundedContext 'TimeService' successfully started in 1.964 seconds


```          

### Publish the time  with console output

You can use curl to publish the time.  
```Console
curl -X POST http://localhost:7502/TimeApplicationService/publishTime
```

Each time you execute curl you should see following output on the console: 

```console                                                          
[qtp380242442-31] INFO io.jexxa.infrastructure.drivenadapterstrategy.messaging.logging.MessageLogger - Begin> Send message
[qtp380242442-31] INFO io.jexxa.infrastructure.drivenadapterstrategy.messaging.logging.MessageLogger - Message           : {"hour":17,"minute":12,"second":34,"nano":873658000}
[qtp380242442-31] INFO io.jexxa.infrastructure.drivenadapterstrategy.messaging.logging.MessageLogger - Destination       : TimeService
[qtp380242442-31] INFO io.jexxa.infrastructure.drivenadapterstrategy.messaging.logging.MessageLogger - Destination-Type  : TOPIC
[qtp380242442-31] INFO io.jexxa.infrastructure.drivenadapterstrategy.messaging.logging.MessageLogger - End> Send message
```

## Run the Application with JMS
Running the application with a locally messaging system is typically required for testing and developing purpose. Therefore, we use the file [jexxa-test.properties](src/test/resources/jexxa-test.properties). 

```console                                                          
mvn clean install
java -jar "-Dio.jexxa.config.import=./src/test/resources/jexxa-test.properties" ./target/timeservice-jar-with-dependencies.jar
```
You will see following (or similar) output
```console
...
[main] INFO io.jexxa.utils.JexxaBanner - Config Information: 
[main] INFO io.jexxa.utils.JexxaBanner - Jexxa Version                  : VersionInfo[version=5.0.1-SNAPSHOT, repository=scm:git:https://github.com/jexxa-projects/Jexxa.git/jexxa-core, projectName=Jexxa-Core, buildTimestamp=2022-06-24 05:10]
[main] INFO io.jexxa.utils.JexxaBanner - Context Version                : VersionInfo[version=1.0.20-SNAPSHOT, repository=scm:git:https://github.com/jexxa-projects/JexxaTutorials.git/timeservice, projectName=TimeService, buildTimestamp=2022-06-24 16:53]
[main] INFO io.jexxa.utils.JexxaBanner - Used Driving Adapter           : [JMSAdapter, RESTfulRPCAdapter]
[main] INFO io.jexxa.utils.JexxaBanner - Used Properties Files          : [/jexxa-application.properties, ./src/test/resources/jexxa-test.properties]
[main] INFO io.jexxa.utils.JexxaBanner - Used Message Sender Strategie  : [JMSSender]
[main] INFO io.jexxa.utils.JexxaBanner - 
[main] INFO io.jexxa.utils.JexxaBanner - Access Information: 
[main] INFO io.jexxa.utils.JexxaBanner - Listening on: http://0.0.0.0:7502
[main] INFO io.jexxa.utils.JexxaBanner - OpenAPI available at: http://0.0.0.0:7502/swagger-docs
[main] INFO io.jexxa.utils.JexxaBanner - JMS Listening on  : tcp://localhost:61616
[main] INFO io.jexxa.utils.JexxaBanner -    * JMS-Topics   : [TimeService]
[main] INFO io.jexxa.utils.JexxaBanner -    * JMS-Queues   : []
[main] INFO io.jexxa.core.JexxaMain - BoundedContext 'TimeService' successfully started in 2.223 seconds
... 
```          

As you can see in the last two lines, we now use the `JMSSender` which is listening on Topic TimeService. 

### Publish the time with JMS ###
 
You can use curl to publish the time.  
```Console
curl -X POST http://localhost:7502/TimeApplicationService/publishTime
```

Each time you execute curl you should see following output on the console: 

```console                                                          
[ActiveMQ Session Task-1] INFO io.jexxa.tutorials.timeservice.infrastructure.drivenadapter.display.MessageDisplayImpl - New Time was published, time: 17:15:18.743772
```
