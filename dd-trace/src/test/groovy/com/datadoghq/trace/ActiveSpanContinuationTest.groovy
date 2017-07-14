package com.datadoghq.trace

import com.datadoghq.trace.writer.ListWriter
import spock.lang.Ignore
import spock.lang.Specification

import java.util.concurrent.Phaser

class ActiveSpanContinuationTest extends Specification {

  def traceCollector = new ListWriter()
  def tracer = new DDTracer(traceCollector)
  def activeSpan = tracer.buildSpan("test").startActive()

  def "calling activate from multiple continuations at once with no child spans tracks separately"() {
    setup:
    def phaser = new Phaser()
    phaser.register()

    for (int i = 0; i < count; i++) {
      phaser.register()
      def capture = activeSpan.capture()
      new Thread({
        phaser.arriveAndAwaitAdvance()
        def activeSpan = capture.activate()
        phaser.arriveAndAwaitAdvance()
        activeSpan.deactivate()
        phaser.arriveAndDeregister()
      }).start()
    }

    activeSpan.deactivate() // allow the trace to be reported when all continuations deactivate

    when:
    phaser.arriveAndAwaitAdvance() //allow threads to activate capture

    then:
    traceCollector == []

    when:
    phaser.arriveAndAwaitAdvance() //allow threads to deactivate their span
    phaser.arriveAndAwaitAdvance() // wait till all threads have deactivated

    then:
    traceCollector.size() == 1
    traceCollector.firstTrace().size() == 1

    where:
    count = 5
  }

  def "concurrent threads with manual spans and continuations report correctly"() {
    setup:
    def phaser = new Phaser()
    phaser.register()

    for (int i = 0; i < count; i++) {
      phaser.register()
      def capture = activeSpan.capture()
      new Thread({
        phaser.arriveAndAwaitAdvance()
        def activeSpan = capture.activate()
        def childSpan = tracer.buildSpan("child " + i).startManual()
        phaser.arriveAndAwaitAdvance()
        childSpan.finish()
        activeSpan.deactivate()
        phaser.arriveAndDeregister()
      }).start()
    }

    activeSpan.deactivate() // allow the trace to be reported when all continuations deactivate

    when:
    phaser.arriveAndAwaitAdvance() //allow threads to activate capture

    then:
    traceCollector == []

    when:
    phaser.arriveAndAwaitAdvance() //allow threads to deactivate their span
    phaser.arriveAndAwaitAdvance() // wait till all threads have deactivated

    traceCollector.size()

    then:
    traceCollector.size() == 1
    def trace = traceCollector.remove(0)
    def parent = trace.remove(0)

    trace.size() == count
    parent.context.parentId == 0

    trace.every {
      it.context.parentId == parent.context.spanId
    }

    where:
    count = 3
  }

  def "concurrent threads with active spans and continuations report correctly"() {
    setup:
    def phaser = new Phaser()
    phaser.register()

    for (int i = 0; i < count; i++) {
      String spanName = "child " + i
      phaser.register()
      def capture = activeSpan.capture()
      new Thread({
        phaser.arriveAndAwaitAdvance()
        def activeSpan = capture.activate()
        def childSpan = tracer.buildSpan(spanName).startActive()
        phaser.arriveAndAwaitAdvance()
        childSpan.deactivate()
        activeSpan.deactivate()
        phaser.arriveAndDeregister()
      }).start()
    }

    activeSpan.deactivate() // allow the trace to be reported when all continuations deactivate

    when:
    phaser.arriveAndAwaitAdvance() //allow threads to activate capture

    then:
    traceCollector == []

    when:
    phaser.arriveAndAwaitAdvance() //allow threads to deactivate their span
    phaser.arriveAndAwaitAdvance() // wait till all threads have deactivated

    traceCollector.size()

    then:
    traceCollector.size() == 1
    def trace = traceCollector.remove(0)
    def parent = trace.remove(0)

    trace.size() == count
    parent.context.parentId == 0

    trace.every {
      it.context.parentId == parent.context.spanId
    }

    where:
    count = 3
  }

  @Ignore("Not yet implemented in ThreadLocalActiveSpan.Continuation")
  def "calling activate more than once results in an error"() {
    setup:
    def capture = activeSpan.capture()

    when:
    activeSpan.deactivate()

    then:
    traceCollector == []

    when:
    capture.activate().deactivate()
    // parent span should be finished at this point.
    then:
    traceCollector == []

    when:
    capture.activate().deactivate()

    then:
    thrown(RuntimeException)
  }
}
