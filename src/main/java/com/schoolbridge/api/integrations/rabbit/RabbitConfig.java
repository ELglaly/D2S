package com.schoolbridge.api.integrations.rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the topic exchanges, queues, bindings, and DLQ skeleton for outbox-published events. Only
 * loaded when the outbox relay is enabled â€” that gates the prod profile and lets test slices opt in
 * via {@code @DynamicPropertySource} without polluting unrelated context loads.
 */
@Configuration
@ConditionalOnProperty(name = "schoolbridge.outbox.relay.enabled", havingValue = "true")
public class RabbitConfig {

  public static final String ANNOUNCEMENT_CREATED_ROUTING_KEY = "announcement.created";
  public static final String ANNOUNCEMENT_RECALLED_ROUTING_KEY = "announcement.recalled";
  public static final String ATTENDANCE_ABSENT_ALERT_ROUTING_KEY = "attendance.absent_alert";
  public static final String ATTENDANCE_LATE_ALERT_ROUTING_KEY = "attendance.late_alert";
  public static final String ATTENDANCE_EXCUSED_ALERT_ROUTING_KEY = "attendance.excused_alert";

  @Value("${schoolbridge.rabbitmq.exchanges.announcements:schoolbridge.announcements}")
  private String announcementsExchangeName;

  @Value(
      "${schoolbridge.rabbitmq.queues.announcement-created:schoolbridge.whatsapp.announcement-created}")
  private String announcementCreatedQueueName;

  @Value(
      "${schoolbridge.rabbitmq.queues.announcement-recalled:schoolbridge.whatsapp.announcement-recalled}")
  private String announcementRecalledQueueName;

  @Value("${schoolbridge.rabbitmq.exchanges.attendance:schoolbridge.attendance}")
  private String attendanceExchangeName;

  @Value(
      "${schoolbridge.rabbitmq.queues.attendance-absent-alert:schoolbridge.whatsapp.attendance-absent-alert}")
  private String attendanceAbsentAlertQueueName;

  @Value(
      "${schoolbridge.rabbitmq.queues.attendance-late-alert:schoolbridge.whatsapp.attendance-late-alert}")
  private String attendanceLateAlertQueueName;

  @Value(
      "${schoolbridge.rabbitmq.queues.attendance-excused-alert:schoolbridge.whatsapp.attendance-excused-alert}")
  private String attendanceExcusedAlertQueueName;

  @Bean
  public TopicExchange announcementsExchange() {
    return new TopicExchange(announcementsExchangeName, true, false);
  }

  @Bean
  public TopicExchange announcementsDlx() {
    return new TopicExchange(announcementsExchangeName + ".dlx", true, false);
  }

  @Bean
  public Queue announcementCreatedQueue() {
    return QueueBuilder.durable(announcementCreatedQueueName)
        .withArgument("x-dead-letter-exchange", announcementsExchangeName + ".dlx")
        .build();
  }

  @Bean
  public Queue announcementCreatedDlq() {
    return QueueBuilder.durable(announcementCreatedQueueName + ".dlq").build();
  }

  @Bean
  public Queue announcementRecalledQueue() {
    return QueueBuilder.durable(announcementRecalledQueueName)
        .withArgument("x-dead-letter-exchange", announcementsExchangeName + ".dlx")
        .build();
  }

  @Bean
  public Queue announcementRecalledDlq() {
    return QueueBuilder.durable(announcementRecalledQueueName + ".dlq").build();
  }

  @Bean
  public Binding announcementCreatedBinding(
      Queue announcementCreatedQueue, TopicExchange announcementsExchange) {
    return BindingBuilder.bind(announcementCreatedQueue)
        .to(announcementsExchange)
        .with(ANNOUNCEMENT_CREATED_ROUTING_KEY);
  }

  @Bean
  public Binding announcementRecalledBinding(
      Queue announcementRecalledQueue, TopicExchange announcementsExchange) {
    return BindingBuilder.bind(announcementRecalledQueue)
        .to(announcementsExchange)
        .with(ANNOUNCEMENT_RECALLED_ROUTING_KEY);
  }

  @Bean
  public Binding announcementCreatedDlqBinding(
      Queue announcementCreatedDlq, TopicExchange announcementsDlx) {
    return BindingBuilder.bind(announcementCreatedDlq)
        .to(announcementsDlx)
        .with(ANNOUNCEMENT_CREATED_ROUTING_KEY);
  }

  @Bean
  public Binding announcementRecalledDlqBinding(
      Queue announcementRecalledDlq, TopicExchange announcementsDlx) {
    return BindingBuilder.bind(announcementRecalledDlq)
        .to(announcementsDlx)
        .with(ANNOUNCEMENT_RECALLED_ROUTING_KEY);
  }

  // -------- Attendance (M8) --------

  @Bean
  public TopicExchange attendanceExchange() {
    return new TopicExchange(attendanceExchangeName, true, false);
  }

  @Bean
  public TopicExchange attendanceDlx() {
    return new TopicExchange(attendanceExchangeName + ".dlx", true, false);
  }

  @Bean
  public Queue attendanceAbsentAlertQueue() {
    return QueueBuilder.durable(attendanceAbsentAlertQueueName)
        .withArgument("x-dead-letter-exchange", attendanceExchangeName + ".dlx")
        .build();
  }

  @Bean
  public Queue attendanceAbsentAlertDlq() {
    return QueueBuilder.durable(attendanceAbsentAlertQueueName + ".dlq").build();
  }

  @Bean
  public Queue attendanceLateAlertQueue() {
    return QueueBuilder.durable(attendanceLateAlertQueueName)
        .withArgument("x-dead-letter-exchange", attendanceExchangeName + ".dlx")
        .build();
  }

  @Bean
  public Queue attendanceLateAlertDlq() {
    return QueueBuilder.durable(attendanceLateAlertQueueName + ".dlq").build();
  }

  @Bean
  public Queue attendanceExcusedAlertQueue() {
    return QueueBuilder.durable(attendanceExcusedAlertQueueName)
        .withArgument("x-dead-letter-exchange", attendanceExchangeName + ".dlx")
        .build();
  }

  @Bean
  public Queue attendanceExcusedAlertDlq() {
    return QueueBuilder.durable(attendanceExcusedAlertQueueName + ".dlq").build();
  }

  @Bean
  public Binding attendanceAbsentAlertBinding(
      Queue attendanceAbsentAlertQueue, TopicExchange attendanceExchange) {
    return BindingBuilder.bind(attendanceAbsentAlertQueue)
        .to(attendanceExchange)
        .with(ATTENDANCE_ABSENT_ALERT_ROUTING_KEY);
  }

  @Bean
  public Binding attendanceLateAlertBinding(
      Queue attendanceLateAlertQueue, TopicExchange attendanceExchange) {
    return BindingBuilder.bind(attendanceLateAlertQueue)
        .to(attendanceExchange)
        .with(ATTENDANCE_LATE_ALERT_ROUTING_KEY);
  }

  @Bean
  public Binding attendanceExcusedAlertBinding(
      Queue attendanceExcusedAlertQueue, TopicExchange attendanceExchange) {
    return BindingBuilder.bind(attendanceExcusedAlertQueue)
        .to(attendanceExchange)
        .with(ATTENDANCE_EXCUSED_ALERT_ROUTING_KEY);
  }

  @Bean
  public Binding attendanceAbsentAlertDlqBinding(
      Queue attendanceAbsentAlertDlq, TopicExchange attendanceDlx) {
    return BindingBuilder.bind(attendanceAbsentAlertDlq)
        .to(attendanceDlx)
        .with(ATTENDANCE_ABSENT_ALERT_ROUTING_KEY);
  }

  @Bean
  public Binding attendanceLateAlertDlqBinding(
      Queue attendanceLateAlertDlq, TopicExchange attendanceDlx) {
    return BindingBuilder.bind(attendanceLateAlertDlq)
        .to(attendanceDlx)
        .with(ATTENDANCE_LATE_ALERT_ROUTING_KEY);
  }

  @Bean
  public Binding attendanceExcusedAlertDlqBinding(
      Queue attendanceExcusedAlertDlq, TopicExchange attendanceDlx) {
    return BindingBuilder.bind(attendanceExcusedAlertDlq)
        .to(attendanceDlx)
        .with(ATTENDANCE_EXCUSED_ALERT_ROUTING_KEY);
  }

  /**
   * Jackson converter for both the outbound publisher and the inbound listener container â€” Spring
   * AMQP wires both sides off this bean automatically.
   */
  @Bean
  public MessageConverter rabbitJacksonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}

