package io.kestra.plugin.jms;

import at.conapi.oss.jms.adapter.AbstractDestination;
import at.conapi.oss.jms.adapter.impl.ConnectionAdapter;
import at.conapi.oss.jms.adapter.impl.ConnectionFactoryAdapter;
import at.conapi.oss.jms.adapter.impl.ConsumerAdapter;
import at.conapi.oss.jms.adapter.impl.SessionAdapter;
import io.kestra.plugin.jms.configuration.ConnectionFactoryConfig;
import io.kestra.plugin.jms.serde.SerdeType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * A Kestra trigger that starts a new flow execution for each message received
 * from a JMS queue or topic.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
        title = "Trigger a flow execution on a JMS message.",
        description = "This trigger listens to a JMS queue or topic and starts a new flow for each message."
)
@Plugin(
        examples = {
                @Example(
                        title = "Start a flow for each message on a specific JMS queue.",
                        full = true,
                        code = """
                id: jms-realtime-flow
                namespace: at.conapi.dev

                tasks:
                  - id: log-message
                    type: io.kestra.plugin.core.log.Log
                    message: "Received from JMS: {{ trigger.data }}"

                triggers:
                  - id: jms-trigger
                    type: io.kestra.plugin.jms.JMSRealtimeTrigger
                    connectionFactoryConfig:
                      type: DIRECT
                      providerJarPaths: kestra:///jms/activemq-client.jar
                      connectionFactoryClass: org.apache.activemq.ActiveMQConnectionFactory
                    destination:
                      name: "kestra.events"
                      destinationType: QUEUE
                """
                )
        }
)
public class JMSRealtimeTrigger extends AbstractTrigger implements RealtimeTriggerInterface, TriggerOutput<JMSMessage> {

    // NOTE: Using @PluginProperty instead of Property<ConnectionFactoryConfig> wrapper.
    // Polymorphic configuration objects with @JsonTypeInfo/@JsonSubTypes don't deserialize correctly
    // when wrapped in Property<>. Jackson cannot resolve the type discriminator ('type' field)
    // during Property deserialization, causing "missing type id property 'type'" errors.
    @PluginProperty
    @NotNull
    private ConnectionFactoryConfig connectionFactoryConfig;

    // NOTE: Using @PluginProperty instead of Property<JMSDestination> wrapper.
    // Nested configuration objects with @PluginProperty fields don't deserialize correctly
    // when wrapped in Property<>. Other Kestra messaging plugins (AMQP, Solace) avoid nested
    // config objects entirely, using flat Property<String> fields instead.
    @PluginProperty
    @NotNull
    @Schema(title = "The destination to consume messages from.")
    private JMSDestination destination;

    @Schema(
            title = "Message selector to only consume specific messages.",
            description = "A JMS message selector expression to filter messages. Uses SQL-92 syntax (e.g., \"JMSPriority > 5 AND type = 'order'\")."
    )
    private String messageSelector;

    @Builder.Default
    @Schema(title = "The format for deserializing the message body.", defaultValue = "STRING")
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) {
        Flux<JMSMessage> messageFlux = Flux.create(emitter -> {
            // We use a self-managed wrapper for JMS resources to ensure they are all closed correctly.
            JmsListener jmsListener = null;
            try {
                // The runContext is needed for rendering variables in the configuration.
                var runContext = conditionContext.getRunContext();

                // Render Property fields
                SerdeType rSerdeType = runContext.render(serdeType).as(SerdeType.class).orElseThrow();

                jmsListener = new JmsListener(runContext, connectionFactoryConfig, destination, messageSelector, rSerdeType, emitter::next, emitter::error);
                jmsListener.start();

                // onDispose is a crucial hook that Kestra calls when the trigger is disabled or the flow is deleted.
                // It ensures we clean up the connection.
                emitter.onDispose(jmsListener::close);
            } catch (Exception e) {
                // If setup fails, we emit the error and the Flux terminates.
                emitter.error(e);
                if(jmsListener != null) {
                    jmsListener.close();
                }
            }
        });

        return messageFlux.map(message -> TriggerService.generateRealtimeExecution(this, conditionContext, context, message));
    }

    /**
     * A helper class to manage the lifecycle of JMS resources for the trigger.
     */
    private static class JmsListener {
        private final io.kestra.core.runners.RunContext runContext;
        private final ConnectionFactoryConfig connectionFactoryConfig;
        private final JMSDestination destination;
        private final String messageSelector;
        private final SerdeType serdeType;
        private final java.util.function.Consumer<JMSMessage> messageConsumer;
        private final java.util.function.Consumer<Throwable> errorConsumer;

        private ConnectionAdapter connection;

        public JmsListener(
                io.kestra.core.runners.RunContext runContext,
                ConnectionFactoryConfig connectionFactoryConfig,
                JMSDestination destination,
                String messageSelector,
                SerdeType serdeType,
                java.util.function.Consumer<JMSMessage> messageConsumer,
                java.util.function.Consumer<Throwable> errorConsumer
        ) {
            this.runContext = runContext;
            this.connectionFactoryConfig = connectionFactoryConfig;
            this.destination = destination;
            this.messageSelector = messageSelector;
            this.serdeType = serdeType;
            this.messageConsumer = messageConsumer;
            this.errorConsumer = errorConsumer;
        }

        public void start() throws Exception {
            JMSConnectionFactory factoryService = new JMSConnectionFactory();
            ConnectionFactoryAdapter factory = factoryService.create(runContext, this.connectionFactoryConfig);

            String rUsername = this.connectionFactoryConfig.getUsername() != null ? runContext.render(this.connectionFactoryConfig.getUsername()) : null;
            String rPassword = this.connectionFactoryConfig.getPassword() != null ? runContext.render(this.connectionFactoryConfig.getPassword()) : null;

            this.connection = (ConnectionAdapter) (rUsername != null ? factory.createConnection(rUsername, rPassword) : factory.createConnection());

            this.connection.setExceptionListener(errorConsumer::accept);

            SessionAdapter session = (SessionAdapter) connection.createSession();

            String rDestName = runContext.render(destination.getDestinationName());
            String rDestType = destination.getDestinationType() == AbstractDestination.DestinationType.QUEUE ? SessionAdapter.QUEUE : SessionAdapter.TOPIC;
            String destinationUrl = String.format("%s://%s", rDestType, rDestName);
            AbstractDestination jmsDestination = session.createDestination(destinationUrl);

            ConsumerAdapter consumer = (ConsumerAdapter) session.createConsumer(jmsDestination, messageSelector);

            consumer.setMessageListener(message -> {
                try {
                    JMSMessage kestraMessage = JMSMessage.of(message, serdeType);
                    messageConsumer.accept(kestraMessage);
                } catch (Exception e) {
                    errorConsumer.accept(e);
                }
            });

            connection.start();
            runContext.logger().info("JMS trigger listener started for destination '{}'", rDestName);
        }

        public void close() {
            if (this.connection != null) {
                try {
                    this.connection.close();
                } catch (Exception e) {
                    runContext.logger().warn("Error closing JMS connection on trigger shutdown.", e);
                }
            }
        }
    }

    // This method is available for a trigger interface but not used for realtime triggers.
    public Optional<Execution> evaluate(ConditionContext conditionContext) throws Exception {
        return Optional.empty();
    }
}
