package tech.mlsql.lang.cmd.compile.internal.gc

import java.util

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * 2/10/2020 WilliamZhu(allwefantasy@gmail.com)
 */
class Parser(tokenizer: Tokenizer) {

  val readTokens = new util.ArrayList[Token]()
  val consumedTokens = new util.ArrayList[Token]()

  def _match(tokens: Scanner.TokenType*): Boolean = {
    if (!lookAhead(tokens: _*)) return false

    // Consume the matched tokens.
    for (_ <- tokens) {
      consume
    }
    true
  }

  def _matchAny(tokens: Scanner.TokenType*): Boolean = {
    for (token <- tokens) {
      if (_match(token)) return true;
    }

    false
  }

  //get last consume token
  def last(offset: Int): Token = {
    consumedTokens.get(offset - 1)
  }


  def consume: Token = {
    // Make sure we've read the token.
    lookAhead(0)
    consumedTokens.add(0, readTokens.remove(0));
    last(1)
  }

  def consumeByType(t: Scanner.TokenType): Token = {
    if (_match(t)) return last(1)
    else {
      val message = String.format("Expected token %s, found %s.",
        t, current);
      throw new RuntimeException(message);
    }

  }


  def lookAhead(distance: Int): Token = {
    // Read in as many as needed.
    while (distance >= readTokens.size) {
      readTokens.add(tokenizer.next)
    }
    // Get the queued token.
    readTokens.get(distance);
  }

  def current: Token = {
    lookAhead(0)
  }

  def lookAhead(tokens: Scanner.TokenType*): Boolean = {
    var i = 0
    for (_ <- tokens) {
      if (lookAhead(i).t != tokens(i)) return false
      i += 1
    }
    true
  }

  def lookAheadAny(tokens: Scanner.TokenType*): Boolean = {
    for (token <- tokens) {
      if (lookAhead(token)) return true
    }
    false
  }

}

object Parser {
  private val PrefixParsers = new mutable.HashMap[Scanner.TokenType, PrefixParser]()
  private val InfixParsers = new mutable.HashMap[Scanner.TokenType, InfixParser]()

  prefix(Scanner.Variable, new VariableParser())
  prefix(Scanner.Int, new LiteralParser())
  prefix(Scanner.Float, new LiteralParser())
  prefix(Scanner.Ident, new LiteralParser())
  prefix(Scanner.String, new LiteralParser())
  prefix(Scanner.RawString, new LiteralParser())
  prefix(Scanner.Lparen, new ParenthesisPrefixParser())

  //  infix(Scanner._And, new AndParser())
  //  infix(Scanner.AndAnd, new AndParser())

  infix(Scanner.Mul, new InfixOperatorParser(8))
  infix(Scanner.Div, new InfixOperatorParser(8))
  infix(Scanner.Rem, new InfixOperatorParser(8))
  infix(Scanner.Add, new InfixOperatorParser(7))
  infix(Scanner.Sub, new InfixOperatorParser(7))
  infix(Scanner.Lss, new InfixOperatorParser(5))
  infix(Scanner.Gtr, new InfixOperatorParser(5))
  infix(Scanner.Leq, new InfixOperatorParser(5))
  infix(Scanner.Geq, new InfixOperatorParser(5))
  infix(Scanner.Eql, new InfixOperatorParser(4))
  infix(Scanner.Neq, new InfixOperatorParser(4))
  infix(Scanner.Neq, new InfixOperatorParser(4))
  infix(Scanner.Assign, new InfixOperatorParser(2))

  def prefix(t: Scanner.TokenType, parser: PrefixParser) = {
    PrefixParsers.put(t, parser)
  }

  def infix(t: Scanner.TokenType, parser: InfixParser) = {
    InfixParsers.put(t, parser)
  }

  def getPrecedence(token: Token): Int = {
    var precedence = 0;

    val parser = InfixParsers.get(token.t);
    if (parser.isDefined) {
      precedence = parser.get.getPrecedence();
    }
    precedence
  }

  def getPrefixParser(t: Scanner.TokenType) = PrefixParsers.get(t)

  def getInfixParser(t: Scanner.TokenType) = InfixParsers.get(t)


}

class StatementParser(tokenizer: Tokenizer) extends Parser(tokenizer) {
  def parseStatement(): TreeNode[_] = {
    if (_match(Scanner.Semi)) return parseStatement()
    if (_match(Scanner._SELECT)) return parseSelect()
    if (matchFuncCall) return parseFuncCall()
    parseExpression()
  }

  def parse(): List[TreeNode[_]] = {
    var exprs = new ArrayBuffer[TreeNode[_]]()
    while (lookAhead(0).t != Scanner.EOF) {
      exprs += parseStatement()
    }
    exprs.toList
  }

  def matchFuncCall = {
    lookAhead(0).t == Scanner.Ident && lookAhead(1).t == Scanner.Lparen
  }


  def parseFuncParams: Seq[Expression] = {
    var exprs = new ArrayBuffer[Expression]()
    exprs += parseStatement().asInstanceOf[Expression]
    while (_match(Scanner.Comma)) {
      exprs += parseStatement().asInstanceOf[Expression]
    }
    exprs
  }

  /**
   * split(:abc,jack,2+3)
   */
  def parseFuncCall() = {
    val funcName = consume
    // consume Lparent
    consume
    val funcCall = FuncCall(Literal(funcName, Types.String), parseFuncParams)
    // consume Rparent
    consume
    funcCall
  }


  def parseExpression(): Expression = {
    parsePrecedence(0)
  }

  def parsePrecedence(precedence: Int): Expression = {
    val token = consume
    if (token.t == Scanner.EOF) {
      return null
    }
    val parserOpt = Parser.getPrefixParser(token.t)
    if (parserOpt.isEmpty) throw new ParserException(String.format("Cannot parse an expression that starts with \"%s\".", token))
    val left = parserOpt.get.parse(this, token)
    left match {
      case ParentGroup(expr) =>
        if (lookAheadAny(Scanner.AndAnd, Scanner._And)) {
          return parseAndAndOrOr(expr)
        }
      case _ =>
    }
    parseInfix(left, precedence)

  }

  def parseAndAndOrOr(left: Expression): Expression = {
    if (_matchAny(Scanner.AndAnd, Scanner._And)) {
      return new AndAndParser().parse(this, left, current)
    }
    if (_matchAny(Scanner.OrOr, Scanner._Or)) {
      return new OrOrParser().parse(this, left, current)
    }
    left
  }

  def parseInfix(_left: Expression, precedence: Int): Expression = {

    var left = _left
    while (precedence < Parser.getPrecedence(current)) {
      val token = consume
      val infix = Parser.getInfixParser(token.t);
      left = infix.get.parse(this, left, token)
      val newLeft = parseAndAndOrOr(left)
      if (newLeft != left) return newLeft
    }
    left
  }

  def parseAssign(): As = {
    val leftExpr = parseStatement()
    if (_match(Scanner._As)) {
      val variable = parseStatement()
      return As(variable.asInstanceOf[Expression], leftExpr.asInstanceOf[Expression])
    }
    throw new ParserException("parse assign fail")
  }

  def parseSelect(): Select = {
    var exprs = new ArrayBuffer[As]()
    exprs += parseAssign()
    while (_match(Scanner.Comma)) {
      exprs += parseAssign()
    }
    Select(exprs.toList)
  }
}

trait PrefixParser {
  def parse(parser: StatementParser, token: Token): Expression
}

class ParenthesisPrefixParser extends PrefixParser {
  override def parse(parser: StatementParser, token: Token): Expression = {
    val expr = parser.parseStatement()
    parser.consumeByType(Scanner.Rparen)
    ParentGroup(expr.asInstanceOf[Expression])
  }
}

class LiteralParser extends PrefixParser {
  def parse(parser: StatementParser, token: Token): Expression = {
    token.t match {
      case Scanner.Int => Literal(token.text, Types.Int)
      case Scanner.Float => Literal(token.text, Types.Float)
      case Scanner.String => Literal(token.text, Types.String)
      case Scanner.RawString => Literal(token.text, Types.String)
      case Scanner.Ident =>
        if (token.text.toLowerCase == "true" || token.text.toLowerCase == "false") {
          Literal(token.text.toBoolean, Types.Boolean)
        } else Literal(token.text, Types.String)
    }
  }
}

class VariableParser extends PrefixParser {
  def parse(parser: StatementParser, token: Token): Expression = {
    token.t match {
      case Scanner.Variable => Variable(token.text, Types.Any)
    }
  }
}


class AndAndParser extends InfixParser {
  override def parse(parser: StatementParser, left: Expression, token: Token): Expression = {
    val value = parser.parseStatement()
    AndAnd(left, value.asInstanceOf[Expression])
  }

  override def getPrecedence(): Int = Precedence.LOGICAL
}

class OrOrParser extends InfixParser {
  override def parse(parser: StatementParser, left: Expression, token: Token): Expression = {
    val value = parser.parseStatement()
    OrOr(left, value.asInstanceOf[Expression])
  }

  override def getPrecedence(): Int = Precedence.LOGICAL
}

trait InfixParser {
  def parse(parser: StatementParser, left: Expression, token: Token): Expression

  def getPrecedence(): Int
}

class InfixOperatorParser(mPrecedence: Int) extends InfixParser {
  override def parse(parser: StatementParser, left: Expression, token: Token): Expression = {
    val value = parser.parseStatement().asInstanceOf[Expression]
    token.t match {
      case Scanner.Eql =>
        Eql(left, value)
      case Scanner.Add =>
        Add(left, value)
      case Scanner.Mul =>
        Mul(left, value)
      case Scanner.Geq =>
        Geq(left, value)

    }

  }

  override def getPrecedence(): Int = mPrecedence
}

class ParserException(msg: String) extends Exception(msg) {

}

