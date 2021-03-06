package org.jetbrains.plugins.ipnb.psi;

import com.intellij.lang.PsiBuilder;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.parsing.ExpressionParsing;
import com.jetbrains.python.parsing.ParsingContext;
import com.jetbrains.python.parsing.StatementParsing;
import com.jetbrains.python.psi.LanguageLevel;
import org.jetbrains.annotations.Nullable;

public class IpnbPyParsingContext extends ParsingContext {
  private final StatementParsing myStatementParser;
  private final ExpressionParsing myExpressionParser;

  public IpnbPyParsingContext(final PsiBuilder builder,
                              LanguageLevel languageLevel,
                              StatementParsing.FUTURE futureFlag) {
    super(builder, languageLevel, futureFlag);
    myStatementParser = new IpnbPyStatementParsing(this, futureFlag);
    myExpressionParser = new IpnbPyExpressionParsing(this);
  }

  @Override
  public ExpressionParsing getExpressionParser() {
    return myExpressionParser;
  }

  @Override
  public StatementParsing getStatementParser() {
    return myStatementParser;
  }

  private static class IpnbPyExpressionParsing extends ExpressionParsing {
    public IpnbPyExpressionParsing(ParsingContext context) {
      super(context);
    }

    @Override
    protected IElementType getReferenceType() {
      return IpnbPyTokenTypes.IPNB_REFERENCE;
    }

    public boolean parsePrimaryExpression(boolean isTargetExpression) {
      final IElementType firstToken = myBuilder.getTokenType();
      if (firstToken == PyTokenTypes.IDENTIFIER) {
        if (isTargetExpression) {
          buildTokenElement(IpnbPyTokenTypes.IPNB_TARGET, myBuilder);
        }
        else {
          buildTokenElement(getReferenceType(), myBuilder);
        }
        return true;
      }
      return super.parsePrimaryExpression(isTargetExpression);
    }
  }

  private static class IpnbPyStatementParsing extends StatementParsing {

    protected IpnbPyStatementParsing(ParsingContext context, @Nullable FUTURE futureFlag) {
      super(context, futureFlag);
    }

    @Override
    protected IElementType getReferenceType() {
      return IpnbPyTokenTypes.IPNB_REFERENCE;
    }

  }
}
