/*
 * SonarQube Java
 * Copyright (C) 2012-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.checks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.sonar.java.model.ExpressionUtils;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.semantic.MethodMatchers;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.Arguments;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LambdaExpressionTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TryStatementTree;

import static org.sonar.java.checks.helpers.UnitTestUtils.FAIL_METHOD_MATCHER;

public abstract class AbstractOneExpectedExceptionRule extends IssuableSubscriptionVisitor {

  private static final String JUNIT4_ASSERT = "org.junit.Assert";

  private static final MethodMatchers JUNIT4_ASSERT_THROWS_WITH_MESSAGE = MethodMatchers.create()
    .ofTypes(JUNIT4_ASSERT)
    .names("assertThrows")
    .addParametersMatcher("java.lang.String", MethodMatchers.ANY, MethodMatchers.ANY)
    .build();

  private static final MethodMatchers ALL_ASSERT_THROWS_MATCHER = MethodMatchers.create()
    .ofTypes(JUNIT4_ASSERT, "org.junit.jupiter.api.Assertions")
    .names("assertThrows")
    .withAnyParameters()
    .build();

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return Arrays.asList(Tree.Kind.TRY_STATEMENT, Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree.is(Tree.Kind.METHOD_INVOCATION)) {
      MethodInvocationTree mit = (MethodInvocationTree) tree;
      Arguments arguments = mit.arguments();
      IdentifierTree identifierTree = ExpressionUtils.methodName(mit);
      if (JUNIT4_ASSERT_THROWS_WITH_MESSAGE.matches(mit)) {
        processAssertThrowsArguments(identifierTree, arguments.get(1), arguments.get(2));
      } else if (arguments.size() >= 2 && ALL_ASSERT_THROWS_MATCHER.matches(mit)) {
        processAssertThrowsArguments(identifierTree, arguments.get(0), arguments.get(1));
      }
    } else {
      TryStatementTree tryStatementTree = (TryStatementTree) tree;
      if (isTryCatchFail(tryStatementTree)) {
        List<Type> expectedTypes = tryStatementTree.catches().stream().map(c -> c.parameter().type().symbolType()).collect(Collectors.toList());
        reportMultipleCallInTree(expectedTypes, tryStatementTree.block(), tryStatementTree.tryKeyword(), "body of this try/catch");
      }
    }
  }

  private void processAssertThrowsArguments(IdentifierTree assertThrowsIdentifier, ExpressionTree expectedType, ExpressionTree executable) {
    if (executable.is(Tree.Kind.LAMBDA_EXPRESSION)) {
      getExpectedException(expectedType).ifPresent(expectedIdentifier ->
        reportMultipleCallInTree(Collections.singletonList(expectedIdentifier.symbolType()),
          ((LambdaExpressionTree) executable).body(), assertThrowsIdentifier, "code of this assertThrows")
      );
    }
  }

  private static Optional<IdentifierTree> getExpectedException(ExpressionTree expectedType) {
    if (expectedType.is(Tree.Kind.MEMBER_SELECT)) {
      MemberSelectExpressionTree memberSelect = ((MemberSelectExpressionTree) expectedType);
      ExpressionTree expression = memberSelect.expression();
      if ("class".equals(memberSelect.identifier().name()) && expression.is(Tree.Kind.IDENTIFIER)) {
        return Optional.of((IdentifierTree) expression);
      }
    }
    return Optional.empty();
  }

  private static boolean isTryCatchFail(TryStatementTree tree) {
    List<StatementTree> statementTrees = tree.block().body();
    if (!statementTrees.isEmpty()) {
      StatementTree lastElement = statementTrees.get(statementTrees.size() - 1);
      if (lastElement.is(Tree.Kind.EXPRESSION_STATEMENT)) {
        ExpressionTree expressionTree = ((ExpressionStatementTree) lastElement).expression();
        if (expressionTree.is(Tree.Kind.METHOD_INVOCATION)) {
          return FAIL_METHOD_MATCHER.matches((MethodInvocationTree) expressionTree);
        }
      }
    }
    return false;
  }

  abstract void reportMultipleCallInTree(List<Type> expectedExceptions, Tree treeToVisit, Tree reportLocation, String placeToRefactor);

  static boolean isChecked(Type type) {
    return !type.isSubtypeOf("java.lang.RuntimeException") && !type.isSubtypeOf("java.lang.Error");
  }

  static List<JavaFileScannerContext.Location> secondaryLocations(List<Tree> methodInvocationTrees) {
    return methodInvocationTrees.stream()
      .map(expr -> new JavaFileScannerContext.Location("Throws an exception", expr))
      .collect(Collectors.toList());
  }

  static class MethodInvocationCollector extends BaseTreeVisitor {
    List<Tree> invocationTree = new ArrayList<>();
    private final Predicate<Symbol> collectPredicate;

    MethodInvocationCollector(Predicate<Symbol> collectPredicate) {
      this.collectPredicate = collectPredicate;
    }

    @Override
    public void visitMethodInvocation(MethodInvocationTree mit) {
      if (collectPredicate.test(mit.symbol())) {
        invocationTree.add(ExpressionUtils.methodName(mit));
      }
      super.visitMethodInvocation(mit);
    }

    @Override
    public void visitNewClass(NewClassTree tree) {
      if (collectPredicate.test(tree.constructorSymbol())) {
        invocationTree.add(tree.identifier());
      }
      super.visitNewClass(tree);
    }

    @Override
    public void visitClass(ClassTree tree) {
      // Skip class
    }

    @Override
    public void visitLambdaExpression(LambdaExpressionTree lambdaExpressionTree) {
      // Skip lambdas
    }
  }

}
