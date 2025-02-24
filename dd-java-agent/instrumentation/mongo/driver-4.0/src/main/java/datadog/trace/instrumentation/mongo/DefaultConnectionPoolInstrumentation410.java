package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(Instrumenter.class)
public class DefaultConnectionPoolInstrumentation410 extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public DefaultConnectionPoolInstrumentation410() {
    super("mongo", "mongo-reactivestreams");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.internal.connection.DefaultConnectionPool";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CallbackWrapper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("getAsync"))
            .and(takesArgument(1, named("com.mongodb.internal.async.SingleResultCallback"))),
        packageName + ".Arg1Advice");
  }
}
