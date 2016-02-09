package mimir.sql;

import java.sql.SQLException
import java.util

import mimir.Database
import mimir.algebra.Join
import mimir.algebra.Select
import mimir.algebra.Union
import mimir.algebra._
import mimir.ctables.{JointSingleVarModel, VGTerm}
import mimir.lenses.{TypeInferenceModel, SchemaMatchingModel, MissingValueModel}
import mimir.util.TypeUtils
import net.sf.jsqlparser.expression.operators.arithmetic._
import net.sf.jsqlparser.expression.operators.conditional._
import net.sf.jsqlparser.expression.operators.relational._
import net.sf.jsqlparser.expression.{BinaryExpression, DoubleValue, Function, LongValue, NullValue, Parenthesis, StringValue, WhenClause}
import net.sf.jsqlparser.{schema, expression}
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select.{SelectBody, PlainSelect, SubSelect, SelectExpressionItem, FromItem, SelectItem}

import scala.collection.JavaConversions._

class RAToSql(db: Database) {
  
  def convert(oper: Operator): SelectBody = 
  {
    oper match {
      case Table(name, sch, metadata) => {
        val body = new PlainSelect();
        val table = new net.sf.jsqlparser.schema.Table(null, name)
        val baseSch = db.getTableSchema(name).get
        body.setFromItem(table)
        body.setSelectItems(
          // new java.util.ArrayList(
            sch.map(_._1).zip(baseSch.map( _._1 )).map(_ match {
              case (external, internal) =>
                val item = new SelectExpressionItem()
                item.setAlias(external)
                item.setExpression(new Column(table, internal))
                item
            }) ++
            metadata.map(_._1).map( (key) => {
              val item = new SelectExpressionItem()
              item.setAlias(key)
              key match {
                case "ROWID_MIMIR" =>
                  item.setExpression(new Column(table, "ROWID"))
                case _ =>
                  item.setExpression(new Column(table, key))
              }
              item
            })
          // )
        )
        body
      }
      case Union(lhs, rhs) => {
        val union = new net.sf.jsqlparser.statement.select.Union()
        val unionList: (SelectBody => List[PlainSelect]) = _ match {
          case s: PlainSelect => List(s)
          case u: net.sf.jsqlparser.statement.select.Union =>
            u.getPlainSelects().toList
        }
        union.setAll(true);
        union.setDistinct(false);
        union.setPlainSelects(
          unionList(convert(lhs)) ++ 
          unionList(convert(rhs))
        )
        union
      }
      case Select(_,_) | Join(_,_) => {
        convert(Project(
          oper.schema.map(_._1).map( (x) => ProjectArg(x, Var(x))).toList, oper
        ))
      }
      case Project(args, src) =>
        val body = new PlainSelect()
        val (cond, sources) = extractSelectsAndJoins(src)
        body.setFromItem(sources.head)
        body.setJoins(new java.util.ArrayList(
          sources.tail.map( (s) => {
            val join = new net.sf.jsqlparser.statement.select.Join()
            join.setRightItem(s)
            join.setSimple(true)
            join
          })
        ))
        if(cond != BoolPrimitive(true)){
          body.setWhere(convert(cond, sources))
        }
        body.setSelectItems(
          new java.util.ArrayList(
            args.map( (arg) => {
              val item = new SelectExpressionItem()
              item.setAlias(arg.column)
              item.setExpression(convert(arg.input, sources))
              item
            })
          )
        )
        body
    }
  }
  
  def extractSelectsAndJoins(oper: Operator): 
    (Expression, List[FromItem]) =
  {
    oper match {
      case Select(cond, child) =>
        val (childCond, childFroms) = extractSelectsAndJoins(child)
        ( Arith.makeAnd(cond, childCond), childFroms )
      case Join(lhs, rhs) =>
        val (lhsCond, lhsFroms) = extractSelectsAndJoins(lhs)
        val (rhsCond, rhsFroms) = extractSelectsAndJoins(rhs)
        ( Arith.makeAnd(lhsCond, rhsCond), 
          lhsFroms ++ rhsFroms
        )
      case _ => 
        val subSelect = new SubSelect()
        subSelect.setSelectBody(convert(oper))
        subSelect.setAlias("SUBQ_"+oper.schema.map(_._1).head)
        (BoolPrimitive(true), List[FromItem](subSelect))
    }
  }

  def bin(b: BinaryExpression, l: Expression, r: Expression): BinaryExpression = {
    bin(b, l, r, List())
  }

  def bin(b: BinaryExpression, l: Expression, r: Expression, sources: List[FromItem]): BinaryExpression =
  {
    b.setLeftExpression(convert(l, sources))
    b.setRightExpression(convert(r, sources))
    b
  }

  def convert(e: Expression): net.sf.jsqlparser.expression.Expression = {
    convert(e, List())
  }

  def convert(e: Expression, sources: List[FromItem]): net.sf.jsqlparser.expression.Expression = {
    e match {
      case IntPrimitive(v) => new LongValue(""+v)
      case StringPrimitive(v) => new StringValue(v)
      case FloatPrimitive(v) => new DoubleValue(""+v)
      case RowIdPrimitive(v) => new StringValue(v)
      case BoolPrimitive(true) =>
        bin(new EqualsTo(), IntPrimitive(1), IntPrimitive(1))
      case BoolPrimitive(false) =>
        bin(new NotEqualsTo(), IntPrimitive(1), IntPrimitive(1))
      case NullPrimitive() => new NullValue()
      case DatePrimitive(y,m,d) => {
        val f = new Function()
        if(db.backend.isInstanceOf[JDBCBackend]
          && db.backend.asInstanceOf[JDBCBackend].driver().equalsIgnoreCase("oracle")
        ) {
          f.setName("TO_DATE")
          f.setParameters(new ExpressionList(
            List[net.sf.jsqlparser.expression.Expression](
              new StringValue(""+y+"-%02d".format(m)+"-%02d".format(d)),
              new StringValue("YYYY-MM-DD")
            )
          ))
          f
        } else {
          f.setName("DATE")
          f.setParameters(new ExpressionList(
            List[net.sf.jsqlparser.expression.Expression](new StringValue(""+y+"-%02d".format(m)+"-%02d".format(d)))
          ))
          f
        }
      }
      case Comparison(Cmp.Eq, l, r)  => bin(new EqualsTo(), l, r, sources)
      case Comparison(Cmp.Neq, l, r) => bin(new NotEqualsTo(), l, r, sources)
      case Comparison(Cmp.Gt, l, r)  => bin(new GreaterThan(), l, r, sources)
      case Comparison(Cmp.Gte, l, r) => bin(new GreaterThanEquals(), l, r, sources)
      case Comparison(Cmp.Lt, l, r)  => bin(new MinorThan(), l, r, sources)
      case Comparison(Cmp.Lte, l, r) => bin(new MinorThanEquals(), l, r, sources)
      case Comparison(Cmp.Like, l, r) => bin(new LikeExpression(), l, r, sources)
      case Comparison(Cmp.NotLike, l, r) => val expr = bin(new LikeExpression(), l, r, sources).asInstanceOf[LikeExpression]; expr.setNot(true); expr
      case Arithmetic(Arith.Add, l, r)  => bin(new Addition(), l, r, sources)
      case Arithmetic(Arith.Sub, l, r)  => bin(new Subtraction(), l, r, sources)
      case Arithmetic(Arith.Mult, l, r) => bin(new Multiplication(), l, r, sources)
      case Arithmetic(Arith.Div, l, r)  => bin(new Division(), l, r, sources)
      case Arithmetic(Arith.And, l, r)  => new AndExpression(convert(l, sources), convert(r, sources))
      case Arithmetic(Arith.Or, l, r)   => new OrExpression(convert(l, sources), convert(r, sources))
      case Var(n) => {
        val src = sources.find(
          (fi) => fi.asInstanceOf[SubSelect].getSelectBody.asInstanceOf[PlainSelect].getSelectItems.exists(
            si => si.asInstanceOf[SelectExpressionItem].getAlias.equalsIgnoreCase(n)
          )
        )
        if(src.isEmpty)
          throw new SQLException("Could not find appropriate source")
        new Column(new net.sf.jsqlparser.schema.Table(null, null), src.head.getAlias+"."+n)
      }
      case Conditional(_, _, _) => {
        val (whenClauses, elseClause) = ExpressionUtils.foldConditionalsToCase(e)
        val caseExpr = new net.sf.jsqlparser.expression.CaseExpression()
        caseExpr.setWhenClauses(new java.util.ArrayList(
          whenClauses.map( (clause) => {
            val whenThen = new WhenClause()
            whenThen.setWhenExpression(convert(clause._1, sources))
            whenThen.setThenExpression(convert(clause._2, sources))
            whenThen
          })
        ))
        caseExpr.setElseExpression(convert(elseClause, sources))
        caseExpr
      }
      case mimir.algebra.Not(mimir.algebra.IsNullExpression(subexp)) => {
        val isNull = new net.sf.jsqlparser.expression.operators.relational.IsNullExpression()
        isNull.setLeftExpression(convert(subexp, sources))
        isNull.setNot(true)
        isNull
      }
      case mimir.algebra.IsNullExpression(subexp) => {
        val isNull = new net.sf.jsqlparser.expression.operators.relational.IsNullExpression()
        isNull.setLeftExpression(convert(subexp, sources))
        isNull
      }
      case Not(subexp) => {
        val parens = new Parenthesis(convert(subexp, sources))
        parens.setNot();
        parens
      }
      case mimir.algebra.Function(name, subexp) => {
        if(name.equals("JOIN_ROWIDS")) {
          if(subexp.size != 2) throw new SQLException("JOIN_ROWIDS should get exactly two arguments")
          return concat(convert(subexp(0), sources), convert(subexp(1), sources), ".")
        }

        if(name.equals("CAST")) {
          val castFunction = new Function()
          castFunction.setName("CAST")
          val explist = new ExpressionList()
          val list = new util.ArrayList[expression.Expression]()
          val alias =
            sources.head.asInstanceOf[SubSelect].getSelectBody.asInstanceOf[PlainSelect]
              .getSelectItems.find(si =>
                si.asInstanceOf[SelectExpressionItem].getAlias.equalsIgnoreCase(subexp(0).asInstanceOf[Var].name)
              ).get.asInstanceOf[SelectExpressionItem].getAlias

          val column = new Column()
          column.setTable(new schema.Table(null, sources.head.getAlias))

          val typeString = subexp(1).toString

          column.setColumnName(
            alias
              +" AS "
              +typeString
          )

          list.add(column)

          explist.setExpressions(list)
          castFunction.setParameters(explist)
          return castFunction
        }

        if(name.equals("TO_DATE")) {
          val toDate = new Function()
          toDate.setName("TO_DATE")
          val explist = new ExpressionList(new util.ArrayList[expression.Expression](subexp.map(convert(_))))
          toDate.setParameters(explist)
          return toDate
        }

        if(subexp.length > 1)
          throw new SQLException("Function " + name + " SQL conversion error")

        convert(subexp(0), sources)
      }
      case VGTerm((_, model), idx, args) => {

        val plainSelect = new PlainSelect()

        /* FROM */
        val backingStore = new schema.Table(null, model.backingStore(idx))
        plainSelect.setFromItem(backingStore)

        /* WHERE */
        val expr = new EqualsTo()
        expr.setLeftExpression(new Column(backingStore, "EXP_LIST"))
        expr.setRightExpression(args.map(convert(_, sources)).reduceLeft(concat(_, _, "|")))
        plainSelect.setWhere(expr)

        /* PROJECT */
        val selItem = new SelectExpressionItem()
        val castFunction = new Function()
        castFunction.setName("CAST")
        val explist = new ExpressionList()
        val list = new util.ArrayList[expression.Expression]()
        val column = new Column()

        /* This is particularly terrible */
        column.setTable(backingStore)
        column.setColumnName("DATA AS INTEGER")
        list.add(column)

        explist.setExpressions(list)
        castFunction.setParameters(explist)
        selItem.setExpression(castFunction)
        val selItemList = new util.ArrayList[SelectItem]()
        selItemList.add(selItem)
        plainSelect.setSelectItems(selItemList)


        val subSelect = new SubSelect()
        subSelect.setSelectBody(plainSelect)
        subSelect
      }
    }
  }


  private def concat(lhs: net.sf.jsqlparser.expression.Expression,
                     rhs: net.sf.jsqlparser.expression.Expression,
                     sep: String): net.sf.jsqlparser.expression.Expression = {
    val e1 = new Concat()
    e1.setLeftExpression(lhs)
    e1.setRightExpression(new StringValue(sep))
    val e2 = new Concat()
    e2.setLeftExpression(e1)
    e2.setRightExpression(rhs)
    e2
  }
  
}