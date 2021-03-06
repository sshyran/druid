/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.filter;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.math.expr.Evals;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.query.BitmapResultFactory;
import org.apache.druid.query.expression.ExprUtils;
import org.apache.druid.query.filter.BitmapIndexSelector;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.query.filter.ValueMatcher;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.ColumnSelector;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.virtual.ExpressionSelectors;

import java.util.Arrays;
import java.util.Set;

public class ExpressionFilter implements Filter
{
  private final Supplier<Expr> expr;
  private final Supplier<Set<String>> requiredBindings;

  public ExpressionFilter(final Supplier<Expr> expr)
  {
    this.expr = expr;
    this.requiredBindings = Suppliers.memoize(() -> expr.get().analyzeInputs().getFreeVariables());
  }

  @Override
  public ValueMatcher makeMatcher(final ColumnSelectorFactory factory)
  {
    final ColumnValueSelector<ExprEval> selector = ExpressionSelectors.makeExprEvalSelector(factory, expr.get());
    return new ValueMatcher()
    {
      @Override
      public boolean matches()
      {
        if (NullHandling.sqlCompatible() && selector.isNull()) {
          return false;
        }
        ExprEval eval = selector.getObject();
        if (eval == null) {
          return false;
        }
        switch (eval.type()) {
          case LONG_ARRAY:
            Long[] lResult = eval.asLongArray();
            return Arrays.stream(lResult).anyMatch(Evals::asBoolean);
          case STRING_ARRAY:
            String[] sResult = eval.asStringArray();
            return Arrays.stream(sResult).anyMatch(Evals::asBoolean);
          case DOUBLE_ARRAY:
            Double[] dResult = eval.asDoubleArray();
            return Arrays.stream(dResult).anyMatch(Evals::asBoolean);
          default:
            return Evals.asBoolean(selector.getLong());
        }
      }

      @Override
      public void inspectRuntimeShape(final RuntimeShapeInspector inspector)
      {
        inspector.visit("selector", selector);
      }
    };
  }

  @Override
  public boolean supportsBitmapIndex(final BitmapIndexSelector selector)
  {
    if (requiredBindings.get().isEmpty()) {
      // Constant expression.
      return true;
    } else if (requiredBindings.get().size() == 1) {
      // Single-column expression. We can use bitmap indexes if this column has an index and does not have
      // multiple values. The lack of multiple values is important because expression filters treat multi-value
      // arrays as nulls, which doesn't permit index based filtering.
      final String column = Iterables.getOnlyElement(requiredBindings.get());
      return selector.getBitmapIndex(column) != null && !selector.hasMultipleValues(column);
    } else {
      // Multi-column expression.
      return false;
    }
  }

  @Override
  public <T> T getBitmapResult(final BitmapIndexSelector selector, final BitmapResultFactory<T> bitmapResultFactory)
  {
    if (requiredBindings.get().isEmpty()) {
      // Constant expression.
      if (expr.get().eval(ExprUtils.nilBindings()).asBoolean()) {
        return bitmapResultFactory.wrapAllTrue(Filters.allTrue(selector));
      } else {
        return bitmapResultFactory.wrapAllFalse(Filters.allFalse(selector));
      }
    } else {
      // Can assume there's only one binding and it has a bitmap index, otherwise supportsBitmapIndex would have
      // returned false and the caller should not have called us.
      final String column = Iterables.getOnlyElement(requiredBindings.get());
      return Filters.matchPredicate(
          column,
          selector,
          bitmapResultFactory,
          value -> expr.get().eval(identifierName -> {
            // There's only one binding, and it must be the single column, so it can safely be ignored in production.
            assert column.equals(identifierName);
            // convert null to Empty before passing to expressions if needed.
            return NullHandling.nullToEmptyIfNeeded(value);
          }).asBoolean()
      );
    }
  }

  @Override
  public boolean supportsSelectivityEstimation(
      final ColumnSelector columnSelector,
      final BitmapIndexSelector indexSelector
  )
  {
    return false;
  }

  @Override
  public double estimateSelectivity(final BitmapIndexSelector indexSelector)
  {
    // Selectivity estimation not supported.
    throw new UnsupportedOperationException();
  }
}
