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
package net.hydromatic.sml;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.compile.Compiler;
import net.hydromatic.sml.eval.Code;
import net.hydromatic.sml.eval.Environment;
import net.hydromatic.sml.eval.Environments;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;

/**
 * Kick the tires.
 */
public class MainTest {
  @Test public void testEmptyRepl() {
    final String[] args = new String[0];
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(new byte[0]);
      new Main(args, in, ps).run();
    }
    Assert.assertThat(out.size(), is(0));
  }

  @Test public void testRepl() {
    final String[] args = new String[0];
    final String ml = "val x = 5;\n"
        + "x;\n"
        + "it + 1;\n";
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(ml.getBytes());
      new Main(args, in, ps).run();
    }
    final String expected = "val x = 5 : int\n"
        + "val it = 5 : int\n"
        + "val it = 6 : int\n";
    Assert.assertThat(out.toString(), is(expected));
  }

  @Test public void testParse() {
    checkParseLiteral("1", isLiteral(BigDecimal.ONE));
    checkParseLiteral("~3.5", isLiteral(new BigDecimal("-3.5")));
    checkParseLiteral("\"a string\"", isLiteral("a string"));

    // true and false are variables, not actually literals
    checkStmt("true", isAst(Ast.Id.class, "true"));
    checkStmt("false", isAst(Ast.Id.class, "false"));

    checkParseDecl("val x = 5", isAst(Ast.VarDecl.class, "val x = 5"));
    checkParseDecl("val x : int = 5",
        isAst(Ast.VarDecl.class, "val x : int = 5"));

    // parentheses creating left precedence, which is the natural precedence for
    // '+', can be removed
    checkStmt("((1 + 2) + 3) + 4",
        isAst(AstNode.class, "1 + 2 + 3 + 4"));

    // parentheses creating right precedence can not be removed
    checkStmt("1 + (2 + (3 + (4)))",
        isAst(AstNode.class, "1 + (2 + (3 + 4))"));

    checkStmt("let val x = 2 in x + (3 + x) + x end",
        isAst(AstNode.class, "let val x = 2 in x + (3 + x) + x end"));

    checkStmt("let val x = 2 and y = 3 in x + y end",
        isAst(AstNode.class, "let val x = 2 and y = 3 in x + y end"));
  }

  @Test public void testEval() {
    // literals
    checkEval("1", is(1));
    checkEval("~2", is(-2));
    checkEval("\"a string\"", is("a string"));
    checkEval("true", is(true));
    checkEval("~10.25", is(-10.25f));

    // operators
    checkEval("2 + 3", is(5));

    // let
    checkEval("let val x = 1 in x + 2 end", is(3));

    // let where variables shadow
    final String letNested = "let\n"
        + "  val x = 1\n"
        + "in\n"
        + "  let\n"
        + "    val x = 2\n"
        + "  in\n"
        + "    x * 3\n"
        + "  end + x\n"
        + "end";
    checkEval(letNested, is(2 * 3 + 1));
  }

  private void withParser(String ml, Consumer<SmlParserImpl> action) {
    final SmlParserImpl parser = new SmlParserImpl(new StringReader(ml));
    action.accept(parser);
  }

  private void checkParseLiteral(String ml, Matcher<Ast.Literal> matcher) {
    withParser(ml, parser -> {
      try {
        final Ast.Literal literal = parser.literal();
        Assert.assertThat(literal, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void checkParseDecl(String ml, Matcher<Ast.VarDecl> matcher) {
    withParser(ml, parser -> {
      try {
        final Ast.VarDecl varDecl = parser.varDecl();
        Assert.assertThat(varDecl, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void checkStmt(String ml, Matcher<AstNode> matcher) {
    try {
      final AstNode statement =
          new SmlParserImpl(new StringReader(ml)).statement();
      Assert.assertThat(statement, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  /** Matches a literal by value. */
  private static Matcher<Ast.Literal> isLiteral(Comparable comparable) {
    return new TypeSafeMatcher<Ast.Literal>() {
      protected boolean matchesSafely(Ast.Literal literal) {
        return literal.value.equals(comparable);
      }

      public void describeTo(Description description) {
        description.appendText("literal with value " + comparable);
      }
    };
  }

  /** Matches an AST node by its string representation. */
  private static <T extends AstNode> Matcher<T> isAst(Class<? extends T> clazz,
      String expected) {
    return new TypeSafeMatcher<T>() {
      protected boolean matchesSafely(T t) {
        return Ast.toString(t).equals(expected);
      }

      public void describeTo(Description description) {
        description.appendText("ast with value " + expected);
      }
    };
  }

  private void checkEval(String ml, Matcher<Object> matcher) {
    try {
      final Ast.Exp expression =
          new SmlParserImpl(new StringReader(ml)).expression();
      final Code code = new Compiler().compile(expression);
      final Environment env = Environments.empty();
      final Object value = code.eval(env);
      Assert.assertThat(value, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }
}

// End MainTest.java
