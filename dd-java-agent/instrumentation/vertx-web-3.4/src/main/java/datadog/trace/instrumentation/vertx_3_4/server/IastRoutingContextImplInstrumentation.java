package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class IastRoutingContextImplInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = IastRoutingContextImplInstrumentation.class.getName();

  public IastRoutingContextImplInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("cookies").and(takesArguments(0)), className + "$CookiesAdvice");
    transformation.applyAdvice(
        named("getCookie").and(takesArguments(1)).and(takesArgument(0, String.class)),
        className + "$GetCookieAdvice");
    transformation.applyAdvice(
        named("reroute").and(takesArguments(2)).and(takesArgument(1, String.class)),
        className + "$RerouteAdvice");
  }

  public static class CookiesAdvice {
    @Advice.OnMethodExit
    public static void onCookies(@Advice.Return final Set<Object> cookies) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      try {
        module.taint(SourceTypes.REQUEST_COOKIE_VALUE, cookies);
      } catch (final Throwable e) {
        module.onUnexpectedException("cookies threw", e);
      }
    }
  }

  public static class GetCookieAdvice {
    @Advice.OnMethodExit
    public static void onGetCookie(@Advice.Return final Object cookie) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      try {
        module.taint(SourceTypes.REQUEST_COOKIE_VALUE, cookie);
      } catch (final Throwable e) {
        module.onUnexpectedException("getCookie threw", e);
      }
    }
  }

  public static class RerouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onReroute(@Advice.Argument(1) final String path) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null) {
        module.onRedirect(path);
      }
    }
  }
}
