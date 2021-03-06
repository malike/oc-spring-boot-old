package io.opencensus.spring.brave.old;

import io.grpc.Context;
import io.opencensus.trace.unsafe.ContextUtils;
import org.apache.commons.logging.Log;
import org.springframework.cloud.sleuth.Span;
import org.springframework.core.NamedThreadLocal;

/**
 * Inspired by the Sleuth's {@code SpanContextHolder}.
 */
final class OpenCensusBraveSpanContextHolder {

  private static final Log log =
      org.apache.commons.logging.LogFactory.getLog(OpenCensusBraveSpanContextHolder.class);
  private static final ThreadLocal<SpanContext> CURRENT_SPAN =
      new NamedThreadLocal<>("Trace Context");

  /** Get the current span out of the thread context */
  static Span getCurrentSpan() {
    return isTracing() ? CURRENT_SPAN.get().span : null;
  }

  /** Set the current span in the thread context */
  static void setCurrentSpan(Span span) {
    if (log.isTraceEnabled()) {
      log.trace("Setting current span " + span);
    }
    push(span, false);
  }

  /**
   * Remove all thread context relating to spans (useful for testing).
   *
   * @see #close() for a better alternative in instrumetation
   */
  static void removeCurrentSpan() {
    CURRENT_SPAN.remove();
    Context.current().withValue(ContextUtils.CONTEXT_SPAN_KEY, null).attach();
  }

  /** Check if there is already a span in the current thread */
  static boolean isTracing() {
    return CURRENT_SPAN.get() != null;
  }

  /**
   * Close the current span and all parents that can be auto closed. On every iteration a function
   * will be applied on the closed Span.
   */
  static void close(SpanFunction spanFunction) {
    SpanContext current = CURRENT_SPAN.get();
    removeCurrentSpan();
    while (current != null) {
      current = current.parent;
      spanFunction.apply(current != null ? current.span : null);
      if (current != null) {
        if (!current.autoClose) {
          setSpanContext(current);
          current = null;
        }
      }
    }
  }

  /** Close the current span and all parents that can be auto closed. */
  static void close() {
    close(new NoOpFunction());
  }

  /**
   * Push a span into the thread context, with the option to have it auto close if any child spans
   * are themselves closed. Use autoClose=true if you start a new span with a parent that wasn't
   * already in thread context.
   */
  static void push(Span span, boolean autoClose) {
    if (isCurrent(span)) {
      return;
    }
    setSpanContext(new SpanContext(span, autoClose));
  }

  static private void setSpanContext(SpanContext spanContext) {
    CURRENT_SPAN.set(spanContext);
    Context.current().withValue(ContextUtils.CONTEXT_SPAN_KEY, spanContext.ocSpan).attach();
  }

  private static boolean isCurrent(Span span) {
    if (span == null || CURRENT_SPAN.get() == null) {
      return false;
    }
    return span.equals(CURRENT_SPAN.get().span);
  }

  private static class SpanContext {
    final Span span;
    final boolean autoClose;
    final SpanContext parent;
    final OpenCensusBraveSpan ocSpan;

    public SpanContext(Span span, boolean autoClose) {
      this.span = span;
      this.autoClose = autoClose;
      this.parent = CURRENT_SPAN.get();
      this.ocSpan = new OpenCensusBraveSpan(span);
    }
  }

  interface SpanFunction {
    void apply(Span span);
  }

  private static class NoOpFunction implements SpanFunction {
    @Override
    public void apply(Span span) {}
  }
}
