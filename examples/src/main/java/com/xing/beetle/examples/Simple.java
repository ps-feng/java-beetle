package com.xing.beetle.examples;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.xing.beetle.*;
import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Simple {

  private Logger log = LoggerFactory.getLogger(Simple.class);

  public static void main(String[] args) {
    try {
      new Simple().run();
    } catch (URISyntaxException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void run() throws URISyntaxException, InterruptedException {

    Client client =
        Client.builder()
            .addBroker(5672)
            .addBroker(5671)
            .setDeduplicationStore(new RedisConfiguration("127.0.0.1"))
            //            .setRedisFailoverMasterFile("/tmp/beetle_redis_master")
            .build();

    // these are the default settings, except of course the name() option
    // You can also pass the builder instead of the built object, if you plan to reuse the builder.
    //        final Exchange simpleXchg =
    // Exchange.builder().name("simpleXchg").topic(true).durable(true).build();
    //        client.registerExchange(simpleXchg);

    final Queue simpleQ =
        Queue.builder()
            .name("simpleQ")
            .key("example.routing.key")
            .amqpName("queueNameOnBroker")
            .exchange("simpleXchg")
            .autoDelete(false)
            .build();
    client.registerQueue(simpleQ);

    final Message redundantMsg =
        Message.builder()
            .name("redundantMsg")
            .key("example.routing.key")
            .exchange("simpleXchg")
            .redundant(true)
            .ttl(2, TimeUnit.MINUTES)
            .build();
    client.registerMessage(redundantMsg);

    final Message nonRedundantMsg =
        Message.builder()
            .name("simpleMsg")
            .key("example.routing.key")
            .exchange("simpleXchg")
            .ttl(2, TimeUnit.MINUTES)
            .build();
    client.registerMessage(nonRedundantMsg);

    final MessageHandler messageHandler =
        new MessageHandler() {
          @Override
          public Callable<HandlerResponse> doProcess(
              final Envelope envelope, final AMQP.BasicProperties properties, final byte[] body) {
            log.warn("Received message {}", new String(body));
            return new Callable<HandlerResponse>() {
              @Override
              public HandlerResponse call() throws Exception {

                Thread.sleep(987);
                log.info(
                    "Handling message...{}",
                    "deliveryTag = "
                        + envelope.getDeliveryTag()
                        + " routingKey = "
                        + envelope.getRoutingKey()
                        + " exchange = "
                        + envelope.getExchange());

                if (new String(body).contains("other")) {
                  throw new RuntimeException("I don't want 'other' messages!");
                }
                return HandlerResponse.ok(envelope, properties, body);
              }
            };
          }
        };
    final ConsumerConfiguration consumerConfig =
        new ConsumerConfiguration(simpleQ, messageHandler)
            .attempts(2)
            .exceptions(2)
            .handlerRetryDelay(2, TimeUnit.SECONDS);
    client.registerHandler(consumerConfig);
    client.start();

    client.publish(redundantMsg, "some payload");
    // the following messages will trigger an exception in the handler
    client.publish("simpleMsg", "some other payload");
    client.publish(redundantMsg, "some other payload");

    log.warn("sleeping for good measure (to actually receive the messages)");
    Thread.sleep(5 * 1000);
    client.stop();
  }
}
