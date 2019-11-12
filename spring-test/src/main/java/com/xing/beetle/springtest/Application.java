package com.xing.beetle.springtest;

import java.util.UUID;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class Application {

  public static void main(String[] args) throws InterruptedException {
    ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
    AmqpTemplate template = context.getBean(AmqpTemplate.class);
    MessageProperties props = new MessageProperties();
    props.setMessageId(UUID.randomUUID().toString());
    Message message = new Message(new byte[0], props);
    for (int i = 0; i < 4; i++) {
      template.send("myQueue", message);
    }
    Thread.sleep(1000);
    context.close();
  }

  @RabbitListener(
      bindings =
          @QueueBinding(
              value = @Queue(value = "myQueue", autoDelete = "true", durable = "true"),
              exchange =
                  @Exchange(
                      value = "auto.exch",
                      autoDelete = "true",
                      ignoreDeclarationExceptions = "true"),
              key = "orderRoutingKey"),
      ackMode = "MANUAL")
  public void processOrder(Message message) {
    System.out.println(message);
  }
}
