package com.xing.beetle.amqp;

import static java.util.Objects.requireNonNull;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import com.xing.beetle.BeetleHeader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;

public class RequeueAtEndConnection implements ConnectionDecorator.Single {

  private class RequeueAtEndChannel implements ChannelDecorator.Single {

    private static final String DEAD_LETTER_SUFFIX = "_dead_letter";

    private final Channel delegate;
    private final Set<String> deadLetterQueues;
    private final SortedSet<Long> deadLetterDeliveryTags;

    public RequeueAtEndChannel(Channel delegate) {
      this.delegate = requireNonNull(delegate);
      this.deadLetterQueues = new HashSet<>();
      this.deadLetterDeliveryTags = new ConcurrentSkipListSet<>();
    }

    @Override
    public String basicConsume(
        String queue,
        boolean autoAck,
        String consumerTag,
        boolean noLocal,
        boolean exclusive,
        Map<String, Object> arguments,
        Consumer callback)
        throws IOException {
      Consumer consumer =
          autoAck
              ? callback
              : new Consumer() {

                @Override
                public void handleCancel(String consumerTag) throws IOException {
                  callback.handleCancel(consumerTag);
                }

                @Override
                public void handleCancelOk(String consumerTag) {
                  callback.handleCancelOk(consumerTag);
                }

                @Override
                public void handleConsumeOk(String consumerTag) {
                  callback.handleConsumeOk(consumerTag);
                }

                @Override
                public void handleDelivery(
                    String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
                    throws IOException {
                  deadLetterCheck(queue, envelope);
                  callback.handleDelivery(consumerTag, envelope, properties, body);
                }

                @Override
                public void handleRecoverOk(String consumerTag) {
                  callback.handleRecoverOk(consumerTag);
                }

                @Override
                public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
                  callback.handleShutdownSignal(consumerTag, sig);
                }
              };
      return delegate.basicConsume(
          queue, autoAck, consumerTag, noLocal, exclusive, arguments, consumer);
    }

    @Override
    public GetResponse basicGet(String queue, boolean autoAck) throws IOException {
      GetResponse response = delegate.basicGet(queue, autoAck);
      if (!autoAck && response != null) {
        deadLetterCheck(queue, response.getEnvelope());
      }
      return response;
    }

    @Override
    public void basicNack(long deliveryTag, boolean multiple, boolean requeue) throws IOException {
      boolean deadLettered = deadLettered(deliveryTag, multiple);
      if (deadLettered) {
        if (requeue) {
          // reject to dead letter queue
          delegate.basicNack(deliveryTag, multiple, false);
        } else {
          // silently drop the message by accepting
          delegate.basicAck(deliveryTag, multiple);
        }
      } else {
        delegate.basicNack(deliveryTag, multiple, requeue);
      }
    }

    @Override
    public void basicReject(long deliveryTag, boolean requeue) throws IOException {
      boolean deadLettered = deadLettered(deliveryTag, false);
      if (deadLettered) {
        if (requeue) {
          // reject to dead letter queue
          delegate.basicReject(deliveryTag, false);
        } else {
          // silently drop the message by accepting
          delegate.basicAck(deliveryTag, false);
        }
      } else {
        delegate.basicReject(deliveryTag, requeue);
      }
    }

    private Map<String, Object> configureDeadLetter(String queue, long ttlInMillis) {
      Map<String, Object> arguments = new HashMap<>();
      arguments.put("x-dead-letter-exchange", "");
      arguments.put("x-dead-letter-routing-key", queue);
      arguments.put("x-message-ttl", ttlInMillis);
      return arguments;
    }

    private Map<String, Object> configureOriginal(Map<String, Object> arguments, String queue) {
      arguments = new HashMap<>(arguments != null ? arguments : Collections.emptyMap());
      arguments.remove(BeetleHeader.REQUEUE_AT_END_DELAY);
      arguments.put("x-dead-letter-exchange", "");
      arguments.put("x-dead-letter-routing-key", queue + DEAD_LETTER_SUFFIX);
      return arguments;
    }

    private void deadLetterCheck(String queue, Envelope envelope) {
      if (deadLetterQueues.contains(queue)) {
        deadLetterDeliveryTags.add(envelope.getDeliveryTag());
      }
    }

    private boolean deadLettered(long deliveryTag, boolean multiple) {
      boolean deadLettered = deadLetterDeliveryTags.contains(deliveryTag);
      if (multiple) {
        deadLetterDeliveryTags.headSet(deliveryTag + 1).clear();
      } else {
        deadLetterDeliveryTags.remove(deliveryTag);
      }
      return deadLettered;
    }

    @Override
    public Channel delegate() {
      return delegate;
    }

    @Override
    public AMQP.Queue.DeclareOk queueDeclare(
        String queue,
        boolean durable,
        boolean exclusive,
        boolean autoDelete,
        Map<String, Object> arguments)
        throws IOException {
      long ttlInMillis = requeueAtEndDelayInMillis;
      if (arguments != null) {
        ttlInMillis = (long) arguments.getOrDefault(BeetleHeader.REQUEUE_AT_END_DELAY, ttlInMillis);
      }
      if (ttlInMillis >= 0) {
        arguments = configureOriginal(arguments, queue);
        Map<String, Object> deadLetterArgs = configureDeadLetter(queue, ttlInMillis);
        AMQP.Queue.DeclareOk ok =
            delegate.queueDeclare(
                queue + DEAD_LETTER_SUFFIX, durable, exclusive, autoDelete, deadLetterArgs);
        if (ok.getQueue() == null || ok.getQueue().isEmpty()) {
          return ok;
        }
        if (invertRequeueParameter) {
          deadLetterQueues.add(queue);
        }
      }
      return delegate.queueDeclare(queue, durable, exclusive, autoDelete, arguments);
    }
  }

  private final Connection delegate;
  private final long requeueAtEndDelayInMillis;
  private final boolean invertRequeueParameter;

  public RequeueAtEndConnection(Connection delegate) {
    this(delegate, -1, false);
  }

  public RequeueAtEndConnection(
      Connection delegate, long requeueAtEndDelayInMillis, boolean invertRequeueParameter) {
    this.delegate = requireNonNull(delegate);
    this.requeueAtEndDelayInMillis = requeueAtEndDelayInMillis;
    this.invertRequeueParameter = invertRequeueParameter;
  }

  @Override
  public Channel createChannel(int channelNumber) throws IOException {
    Channel channel =
        channelNumber >= 0 ? delegate.createChannel(channelNumber) : delegate.createChannel();
    return new RequeueAtEndChannel(channel);
  }

  @Override
  public Connection delegate() {
    return delegate;
  }
}
