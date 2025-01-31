/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package net.hydromatic.morel;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.Analyzer;
import net.hydromatic.morel.compile.CalciteCompiler;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Inliner;
import net.hydromatic.morel.compile.Relationalizer;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.util.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.hydromatic.morel.Matchers.hasMoniker;
import static net.hydromatic.morel.Matchers.isAst;
import static net.hydromatic.morel.Matchers.throwsA;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import static java.util.Objects.requireNonNull;

/** Fluent test helper. */
class Ml {
  private final String ml;
  @Nullable private final Pos pos;
  private final Map<String, DataSet> dataSetMap;
  private final Map<Prop, Object> propMap;

  Ml(String ml, @Nullable Pos pos, Map<String, DataSet> dataSetMap,
      Map<Prop, Object> propMap) {
    this.ml = ml;
    this.pos = pos;
    this.dataSetMap = ImmutableMap.copyOf(dataSetMap);
    this.propMap = ImmutableMap.copyOf(propMap);
  }

  /** Creates an {@code Ml}. */
  static Ml ml(String ml) {
    return new Ml(ml, null, ImmutableMap.of(), ImmutableMap.of());
  }

  /** Creates an {@code Ml} with an error position in it. */
  static Ml ml(String ml, char delimiter) {
    Pair<String, Pos> pair = Pos.split(ml, delimiter, "stdIn");
    return new Ml(pair.left, pair.right, ImmutableMap.of(), ImmutableMap.of());
  }

  /** Runs a task and checks that it throws an exception.
   *
   * @param runnable Task to run
   * @param matcher Checks whether exception is as expected
   */
  static void assertError(Runnable runnable,
      Matcher<Throwable> matcher) {
    try {
      runnable.run();
      fail("expected error");
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
  }

  Ml withParser(Consumer<MorelParserImpl> action) {
    final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
    action.accept(parser);
    return this;
  }

  Ml assertParseLiteral(Matcher<Ast.Literal> matcher) {
    return withParser(parser -> {
      try {
        final Ast.Literal literal = parser.literalEof();
        assertThat(literal, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertParseDecl(Matcher<Ast.Decl> matcher) {
    return withParser(parser -> {
      try {
        final Ast.Decl decl = parser.declEof();
        assertThat(decl, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertParseDecl(Class<? extends Ast.Decl> clazz,
      String expected) {
    return assertParseDecl(isAst(clazz, false, expected));
  }

  Ml assertParseStmt(Matcher<AstNode> matcher) {
    return withParser(parser -> {
      try {
        final AstNode statement = parser.statementEof();
        assertThat(statement, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertParseStmt(Class<? extends AstNode> clazz,
      String expected) {
    return assertParseStmt(isAst(clazz, false, expected));
  }

  /** Checks that an expression can be parsed and returns the given string
   * when unparsed. */
  Ml assertParse(String expected) {
    return assertParse(false, expected);
  }

  /** Checks that an expression can be parsed and returns the given string
   * when unparsed, optionally with full parentheses. */
  Ml assertParse(boolean parenthesized, String expected) {
    return assertParseStmt(isAst(AstNode.class, parenthesized, expected));
  }

  /** Checks that an expression can be parsed and returns the identical
   * expression when unparsed. */
  Ml assertParseSame() {
    return assertParse(ml.replaceAll("[\n ]+", " "));
  }

  Ml assertParseThrowsParseException(Matcher<String> matcher) {
    return assertParseThrows(throwsA(ParseException.class, matcher));
  }

  Ml assertParseThrowsIllegalArgumentException(Matcher<String> matcher) {
    return assertParseThrows(throwsA(IllegalArgumentException.class, matcher));
  }

  Ml assertParseThrows(Matcher<Throwable> matcher) {
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statementEof();
      fail("expected error, got " + statement);
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
    return this;
  }

  private Ml withValidate(BiConsumer<TypeResolver.Resolved, Calcite> action) {
    return withParser(parser -> {
      try {
        parser.zero("stdIn");
        final AstNode statement = parser.statementEof();
        final Calcite calcite = Calcite.withDataSets(dataSetMap);
        final TypeResolver.Resolved resolved =
            Compiles.validateExpression(statement, calcite.foreignValues());
        action.accept(resolved, calcite);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertType(Matcher<Type> matcher) {
    return withValidate((resolved, calcite) ->
        assertThat(resolved.typeMap.getType(resolved.exp()), matcher));
  }

  Ml assertType(String expected) {
    return assertType(hasMoniker(expected));
  }

  Ml assertTypeThrows(Function<Pos, Matcher<Throwable>> matcherSupplier) {
    return assertTypeThrows(matcherSupplier.apply(pos));
  }

  Ml assertTypeThrows(Matcher<Throwable> matcher) {
    assertError(() ->
            withValidate((resolved, calcite) ->
                fail("expected error")),
        matcher);
    return this;
  }

  Ml withPrepare(Consumer<CompiledStatement> action) {
    return withParser(parser -> {
      try {
        final TypeSystem typeSystem = new TypeSystem();
        final AstNode statement = parser.statementEof();
        final Environment env = Environments.empty();
        final Session session = new Session();
        final List<CompileException> warningList = new ArrayList<>();
        final CompiledStatement compiled =
            Compiles.prepareStatement(typeSystem, session, env, statement,
                null, warningList::add);
        action.accept(compiled);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertCalcite(Matcher<String> matcher) {
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statementEof();
      final TypeSystem typeSystem = new TypeSystem();

      final Calcite calcite = Calcite.withDataSets(dataSetMap);
      final TypeResolver.Resolved resolved =
          Compiles.validateExpression(statement, calcite.foreignValues());
      final Environment env = resolved.env;
      final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
      final Resolver resolver = Resolver.of(resolved.typeMap, env);
      final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
      assertThat(valDecl3, instanceOf(Core.NonRecValDecl.class));
      final RelNode rel =
          new CalciteCompiler(typeSystem, calcite)
              .toRel(env, Compiles.toExp((Core.NonRecValDecl) valDecl3));
      requireNonNull(rel);
      final String relString = RelOptUtil.toString(rel);
      assertThat(relString, matcher);
      return this;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  /** Asserts that after parsing the current expression and converting it to
   * Core, the Core string converts to the expected value. Which is usually
   * the original string. */
  public Ml assertCoreString(Matcher<String> matcher) {
    return assertCoreString(null, matcher, null);
  }

  /** As {@link #assertCoreString(Matcher)} but also checks how the Core
   * string has changed after inlining. */
  public Ml assertCoreString(@Nullable Matcher<String> beforeMatcher,
      Matcher<String> matcher,
      @Nullable Matcher<String> inlinedMatcher) {
    final AstNode statement;
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      statement = parser.statementEof();
    } catch (ParseException parseException) {
      throw new RuntimeException(parseException);
    }

    final Calcite calcite = Calcite.withDataSets(dataSetMap);
    final TypeResolver.Resolved resolved =
        Compiles.validateExpression(statement, calcite.foreignValues());
    final TypeSystem typeSystem = resolved.typeMap.typeSystem;
    final Environment env = resolved.env;
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Resolver resolver = Resolver.of(resolved.typeMap, env);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);

    if (beforeMatcher != null) {
      // "beforeMatcher", if present, checks the expression before any inlining
      assertThat(valDecl3, instanceOf(Core.NonRecValDecl.class));
      assertThat(((Core.NonRecValDecl) valDecl3).exp.toString(), beforeMatcher);
    }

    final int inlineCount = inlinedMatcher == null ? 1 : 10;
    final Relationalizer relationalizer = Relationalizer.of(typeSystem, env);
    Core.ValDecl valDecl4 = valDecl3;
    for (int i = 0; i < inlineCount; i++) {
      final Analyzer.Analysis analysis =
          Analyzer.analyze(typeSystem, env, valDecl4);
      final Inliner inliner = Inliner.of(typeSystem, env, analysis);
      final Core.ValDecl valDecl5 = valDecl4;
      valDecl4 = valDecl5.accept(inliner);
      valDecl4 = valDecl4.accept(relationalizer);
      if (i == 0) {
        // "matcher" checks the expression after one inlining pass
        assertThat(valDecl4, instanceOf(Core.NonRecValDecl.class));
        assertThat(((Core.NonRecValDecl) valDecl4).exp.toString(), matcher);
      }
      if (valDecl4 == valDecl5) {
        break;
      }
    }
    if (inlinedMatcher != null) {
      // "inlinedMatcher", if present, checks the expression after all inlining
      // passes
      assertThat(valDecl4, instanceOf(Core.NonRecValDecl.class));
      assertThat(((Core.NonRecValDecl) valDecl4).exp.toString(), inlinedMatcher);
    }
    return this;
  }

  Ml assertAnalyze(Matcher<Object> matcher) {
    final AstNode statement;
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      statement = parser.statementEof();
    } catch (ParseException parseException) {
      throw new RuntimeException(parseException);
    }
    final TypeSystem typeSystem = new TypeSystem();

    final Environment env =
        Environments.env(typeSystem, ImmutableMap.of());
    final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, valDecl, typeSystem);
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Resolver resolver = Resolver.of(resolved.typeMap, env);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
    final Analyzer.Analysis analysis =
        Analyzer.analyze(typeSystem, env, valDecl3);
    assertThat(ImmutableSortedMap.copyOf(analysis.map).toString(), matcher);
    return this;
  }

  Ml assertMatchCoverage(MatchCoverage expectedCoverage) {
    final Function<Pos, Matcher<Throwable>> exceptionMatcherFactory;
    final Matcher<List<Throwable>> warningsMatcher;
    switch (expectedCoverage) {
    case OK:
      // Expect no errors or warnings
      exceptionMatcherFactory = null;
      warningsMatcher = isEmptyList();
      break;
    case REDUNDANT:
      exceptionMatcherFactory = pos -> throwsA("match redundant", pos);
      warningsMatcher = isEmptyList();
      break;
    case NON_EXHAUSTIVE_AND_REDUNDANT:
      exceptionMatcherFactory = pos ->
          throwsA("match nonexhaustive and redundant", pos);
      warningsMatcher = isEmptyList();
      break;
    case NON_EXHAUSTIVE:
      exceptionMatcherFactory = null;
      warningsMatcher =
          new CustomTypeSafeMatcher<List<Throwable>>("non-empty list") {
            @Override protected boolean matchesSafely(List<Throwable> list) {
              return list.stream()
                  .anyMatch(e ->
                      e instanceof CompileException
                          && e.getMessage().equals("match nonexhaustive"));
            }
          };
      break;
    default:
      // Java doesn't know the switch is exhaustive; how ironic
      throw new AssertionError(expectedCoverage);
    }
    return assertEval(notNullValue(), null, exceptionMatcherFactory,
        warningsMatcher);
  }

  private static <E> Matcher<List<E>> isEmptyList() {
    return new CustomTypeSafeMatcher<List<E>>("empty list") {
      @Override protected boolean matchesSafely(List<E> list) {
        return list.isEmpty();
      }
    };
  }

  Ml assertPlan(Matcher<Code> planMatcher) {
    return assertEval(null, planMatcher, null, null);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  <E> Ml assertEvalIter(Matcher<Iterable<E>> matcher) {
    return assertEval((Matcher) matcher);
  }

  Ml assertEval(Matcher<Object> resultMatcher) {
    return assertEval(resultMatcher, null, null, null);
  }

  Ml assertEval(@Nullable Matcher<Object> resultMatcher,
      @Nullable Matcher<Code> planMatcher,
      @Nullable Function<Pos, Matcher<Throwable>> exceptionMatcherFactory,
      @Nullable Matcher<List<Throwable>> warningsMatcher) {
    final Matcher<Throwable> exceptionMatcher =
        exceptionMatcherFactory == null
            ? null
            : exceptionMatcherFactory.apply(pos);
    return withValidate((resolved, calcite) -> {
      final Session session = new Session();
      session.map.putAll(propMap);
      eval(session, resolved.env, resolved.typeMap.typeSystem, resolved.node,
          calcite, resultMatcher, planMatcher, exceptionMatcher,
          warningsMatcher);
    });
  }

  Ml assertEvalThrows(
      Function<Pos, Matcher<Throwable>> exceptionMatcherFactory) {
    return assertEval(null, null, exceptionMatcherFactory, null);
  }

  @CanIgnoreReturnValue
  private <E extends Throwable> Object eval(Session session, Environment env,
      TypeSystem typeSystem, AstNode statement, Calcite calcite,
      @Nullable Matcher<Object> resultMatcher,
      @Nullable Matcher<Code> planMatcher,
      @Nullable Matcher<Throwable> exceptionMatcher,
      @Nullable Matcher<List<Throwable>> warningsMatcher) {
    final List<Binding> bindings = new ArrayList<>();
    final List<Throwable> warningList = new ArrayList<>();
    try {
      CompiledStatement compiledStatement =
          Compiles.prepareStatement(typeSystem, session, env, statement,
              calcite, warningList::add);
      session.withoutHandlingExceptions(session1 ->
          compiledStatement.eval(session1, env, line -> {}, bindings::add));
      if (exceptionMatcher != null) {
        fail("expected exception, but none was thrown");
      }
    } catch (Throwable e) {
      if (exceptionMatcher == null) {
        throw e;
      }
      assertThat(e, exceptionMatcher);
    }
    if (warningsMatcher != null) {
      assertThat(warningList, warningsMatcher);
    }
    final Object result;
    if (statement instanceof Ast.Exp) {
      result = bindingValue(bindings, "it");
    } else if (bindings.size() == 1) {
      result = bindings.get(0).value;
    } else {
      Map<String, Object> map = new LinkedHashMap<>();
      bindings.forEach(b -> {
        if (!b.id.name.equals("it")) {
          map.put(b.id.name, b.value);
        }
      });
      result = map;
    }
    if (resultMatcher != null) {
      assertThat(result, resultMatcher);
    }
    if (planMatcher != null) {
      final String plan = Codes.describe(session.code);
      assertThat(session.code, planMatcher);
    }
    return result;
  }

  private Object bindingValue(List<Binding> bindings, String name) {
    for (Binding binding : bindings) {
      if (binding.id.name.equals(name)) {
        return binding.value;
      }
    }
    return null;
  }

  Ml assertEvalError(Function<Pos, Matcher<Throwable>> matcherSupplier) {
    assertThat(pos, notNullValue());
    final Matcher<Throwable> matcher = matcherSupplier.apply(pos);
    try {
      assertEval(notNullValue());
      fail("expected error");
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
    return this;
  }

  Ml assertEvalWarnings(Matcher<List<Throwable>> warningsMatcher) {
    return assertEval(notNullValue(), null, null, warningsMatcher);
  }

  Ml assertEvalSame() {
    final Matchers.LearningMatcher<Object> resultMatcher =
        Matchers.learning(Object.class);
    return with(Prop.HYBRID, false)
        .assertEval(resultMatcher)
        .with(Prop.HYBRID, true)
        .assertEval(Matchers.isUnordered(resultMatcher.get()));
  }

  Ml assertError(Matcher<String> matcher) {
    // TODO: execute code, and check error occurs
    return this;
  }

  Ml assertError(String expected) {
    return assertError(is(expected));
  }

  Ml withBinding(String name, DataSet dataSet) {
    return new Ml(ml, pos, plus(dataSetMap, name, dataSet), propMap);
  }

  Ml with(Prop prop, Object value) {
    return new Ml(ml, pos, dataSetMap, plus(propMap, prop, value));
  }

  /** Returns a map plus (adding or overwriting) one (key, value) entry. */
  private static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    final ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
    if (map.containsKey(k)) {
      map.forEach((k2, v2) -> {
        if (!k2.equals(k)) {
          builder.put(k, v);
        }
      });
    } else {
      builder.putAll(map);
    }
    builder.put(k, v);
    return builder.build();
  }

  /** Whether a list of patterns is exhaustive (covers all possible input
   * values), redundant (covers some input values more than once), both or
   * neither. */
  enum MatchCoverage {
    NON_EXHAUSTIVE,
    REDUNDANT,
    NON_EXHAUSTIVE_AND_REDUNDANT,
    OK
  }
}

// End Ml.java
