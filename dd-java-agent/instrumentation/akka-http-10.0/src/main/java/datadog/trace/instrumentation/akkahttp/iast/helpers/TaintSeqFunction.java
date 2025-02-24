package datadog.trace.instrumentation.akkahttp.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import scala.Tuple1;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.immutable.Seq;
import scala.compat.java8.JFunction1;

public class TaintSeqFunction
    implements JFunction1<
        Tuple1<Seq<Tuple2<String, String>>>, Tuple1<Seq<Tuple2<String, String>>>> {
  public static final TaintSeqFunction INSTANCE = new TaintSeqFunction();

  @Override
  public Tuple1<Seq<Tuple2<String, String>>> apply(Tuple1<Seq<Tuple2<String, String>>> v1) {
    Seq<Tuple2<String, String>> seq = v1._1;

    PropagationModule prop = InstrumentationBridge.PROPAGATION;
    if (prop == null || seq == null || seq.isEmpty()) {
      return v1;
    }

    final IastContext ctx = IastContext.Provider.get();
    Iterator<Tuple2<String, String>> iterator = seq.iterator();
    Set<String> seenKeys = Collections.newSetFromMap(new IdentityHashMap<>());
    while (iterator.hasNext()) {
      Tuple2<String, String> t = iterator.next();
      String name = t._1();
      String value = t._2();
      if (seenKeys.add(name)) {
        prop.taint(ctx, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
      }
      prop.taint(ctx, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
    }

    return v1;
  }
}
