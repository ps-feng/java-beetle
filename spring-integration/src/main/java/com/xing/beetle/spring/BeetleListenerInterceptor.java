package com.xing.beetle.spring;

import static java.util.Objects.requireNonNull;

import com.rabbitmq.client.Channel;
import com.xing.beetle.dedup.api.MessageListener;
import com.xing.beetle.dedup.spi.DedupStore;
import com.xing.beetle.dedup.spi.MessageAdapter;
import com.xing.beetle.util.ExceptionSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

public class BeetleListenerInterceptor implements MethodInterceptor {

  private static class SpringMessageAdaptor implements MessageAdapter<Message> {

    private final Channel channel;
    private final boolean needToAck;
    private final boolean rejectAndRequeue;

    public SpringMessageAdaptor(Channel channel, boolean needToAck, boolean rejectAndRequeue) {
      this.channel = requireNonNull(channel);
      this.needToAck = needToAck;
      this.rejectAndRequeue = rejectAndRequeue;
    }

    @Override
    public void acknowledge(Message message) {
      if (needToAck) {
        try {
          channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException e) {
          ExceptionSupport.sneakyThrow(e);
        }
      }
    }

    @Override
    public String keyOf(Message message) {
      return message.getMessageProperties().getMessageId();
    }

    @Override
    public long expiresAt(Message message) {
      Object expiresAt = message.getMessageProperties().getHeader("expires_at");
      if (expiresAt == null) {
        return Long.MAX_VALUE;
      } else if (expiresAt instanceof Number) {
        return ((Number) expiresAt).longValue();
      } else if (expiresAt instanceof String) {
        return Long.parseLong((String) expiresAt);
      } else {
        throw new IllegalArgumentException(
            "Unexpected expires_at header value " + expiresAt.getClass());
      }
    }

    @Override
    public void requeue(Message message) {
      if (needToAck) {
        try {
          channel.basicReject(message.getMessageProperties().getDeliveryTag(), rejectAndRequeue);
        } catch (IOException e) {
          ExceptionSupport.sneakyThrow(e);
        }
      }
    }
  }

  private final DedupStore store;
  private final RabbitListenerEndpointRegistry registry;
  private final boolean rejectAndRequeue;
  private Map<String, AcknowledgeMode> acknowledgeModes;

  public BeetleListenerInterceptor(
      DedupStore store, RabbitListenerEndpointRegistry registry, boolean rejectAndRequeue) {
    this.store = requireNonNull(store);
    this.registry = requireNonNull(registry);
    this.rejectAndRequeue = rejectAndRequeue;
  }

  @EventListener
  void onApplicationStarted(ContextRefreshedEvent event) {
    acknowledgeModes =
        registry.getListenerContainers().stream()
            .filter(AbstractMessageListenerContainer.class::isInstance)
            .map(AbstractMessageListenerContainer.class::cast)
            .flatMap(c -> Stream.of(c.getQueueNames()).map(q -> Map.entry(q, c)))
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAcknowledgeMode()));
  }

  private MessageAdapter<Message> adapter(Channel channel, Message message) {
    String queue = message.getMessageProperties().getConsumerQueue();
    AcknowledgeMode mode = acknowledgeModes.getOrDefault(queue, AcknowledgeMode.AUTO);
    return new SpringMessageAdaptor(channel, mode == AcknowledgeMode.MANUAL, rejectAndRequeue);
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Channel channel = (Channel) invocation.getArguments()[0];
    Object data = invocation.getArguments()[1];
    boolean multiple = data instanceof List;
    @SuppressWarnings("unchecked")
    List<Message> messages =
        multiple
            ? (List<Message>) data
            : new ArrayList<>(Collections.singletonList((Message) data));
    MessageListener<Message> listener =
        msg -> {
          invocation.getArguments()[1] = multiple ? Collections.singletonList(msg) : msg;
          invocation.proceed();
        };
    messages.forEach(msg -> store.handle(msg, adapter(channel, msg), listener));
    return null;
  }
}
