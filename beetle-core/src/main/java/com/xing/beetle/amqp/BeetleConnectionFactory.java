package com.xing.beetle.amqp;

import com.rabbitmq.client.*;
import com.xing.beetle.util.ExceptionSupport.Supplier;
import com.xing.beetle.util.RetryExecutor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class BeetleConnectionFactory extends ConnectionFactory {

  private RetryExecutor connectionEstablishingExecutor = RetryExecutor.SYNCHRONOUS;
  private boolean invertRequeueParameter = false;
  private BeetleAmqpConfiguration beetleAmqpConfiguration;

  public BeetleConnectionFactory(BeetleAmqpConfiguration beetleAmqpConfiguration) {
    this.beetleAmqpConfiguration = beetleAmqpConfiguration;
  }

  private Supplier<RecoverableConnection> connection(
      ExecutorService executor, AddressResolver resolver, String clientProvidedName) {
    return () ->
        (RecoverableConnection) super.newConnection(executor, resolver, clientProvidedName);
  }

  @Override
  protected AddressResolver createAddressResolver(List<Address> addresses) {
    return new ListAddressResolver(addresses);
  }

  public RetryExecutor getConnectionEstablishingExecutor() {
    return connectionEstablishingExecutor;
  }

  @Override
  public Connection newConnection(
      ExecutorService executor, AddressResolver addressResolver, String clientProvidedName)
      throws IOException, TimeoutException {
    setAutomaticRecoveryEnabled(true);
    RetryExecutor retryExecutor =
        executor != null
            ? connectionEstablishingExecutor.withExecutor(executor)
            : connectionEstablishingExecutor;
    List<Connection> connections =
        addressResolver.getAddresses().stream()
            .map(Collections::singletonList)
            .map(ListAddressResolver::new)
            .map(res -> connection(executor, res, clientProvidedName))
            .map(retryExecutor::supply)
            .map(RetryableConnection::new)
            .map(
                c -> new RequeueAtEndConnection(c, beetleAmqpConfiguration, invertRequeueParameter))
            .map(MultiPlexingConnection::new)
            .collect(Collectors.toList());
    return new BeetleConnection(connections);
  }

  public void setConnectionEstablishingExecutor(RetryExecutor connectionEstablishExecutor) {
    this.connectionEstablishingExecutor = connectionEstablishExecutor;
  }

  public boolean isInvertRequeueParameter() {
    return invertRequeueParameter;
  }

  public void setInvertRequeueParameter(boolean invertRequeueParameter) {
    this.invertRequeueParameter = invertRequeueParameter;
  }
}