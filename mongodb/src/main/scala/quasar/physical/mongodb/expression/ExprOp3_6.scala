/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.mongodb.expression

import slamdata.Predef._
import quasar.fp._
import quasar.fp.ski._
import quasar.physical.mongodb.{Bson}

import matryoshka._
import matryoshka.data.Fix
import scalaz._, Scalaz._

/** "Pipeline" operators added in MongoDB version 3.6 */
trait ExprOp3_6F[A]

object ExprOp3_6F {

  final case class $mergeObjectsF[A](docs: List[A]) extends ExprOp3_6F[A]
  final case class $dateFromStringF[A](string: A, timezone: A) extends ExprOp3_6F[A]
  final case class $dateFromPartsF[A](
    year: A,
    month: A,
    day: A,
    hour: A,
    minute: A,
    second: A,
    milliseconds: A,
    timezone: A
  ) extends ExprOp3_6F[A]
  final case class $dateToPartsF[A](date: A, timezone: A, iso8601: Boolean) extends ExprOp3_6F[A]

  implicit val equal: Delay[Equal, ExprOp3_6F] =
    new Delay[Equal, ExprOp3_6F] {
      def apply[A](eq: Equal[A]) = {
        implicit val EQ: Equal[A] = eq
        Equal.equal {
          case ($mergeObjectsF(d1), $mergeObjectsF(d2)) => d1 ≟ d2
          case ($dateFromStringF(s1, t1), $dateFromStringF(s2, t2)) => s1 ≟ s2 && t1 ≟ t2
          case ($dateFromPartsF(y1, m1, d1, h1, mi1, s1, ms1, t1), $dateFromPartsF(y2, m2, d2, h2, mi2, s2, ms2, t2)) =>
            y1 ≟ y2 && m1 ≟ m2 && d1 ≟ d2 && h1 ≟ h2 && mi1 ≟ mi2 && s1 ≟ s2 && ms1 ≟ ms2 && t1 ≟ t2
          case ($dateToPartsF(d1, t1, i1), $dateToPartsF(d2, t2, i2)) =>
            d1 ≟ d2 && t1 ≟ t2 && i1 ≟ i2
          case _ => false
        }
      }
    }

  implicit val traverse: Traverse[ExprOp3_6F] = new Traverse[ExprOp3_6F] {
    def traverseImpl[G[_], A, B](fa: ExprOp3_6F[A])(f: A => G[B])(implicit G: Applicative[G]):
        G[ExprOp3_6F[B]] =
      fa match {
        case $mergeObjectsF(d) => G.map(d.traverse(f))($mergeObjectsF(_))
        case $dateFromStringF(s, t) => (f(s) |@| f(t))($dateFromStringF(_, _))
        case $dateFromPartsF(y, m, d, h, mi, s, ms, t) =>
          (f(y) |@| f(m) |@| f(d) |@| f(h) |@| f(mi) |@| f(s) |@| f(ms) |@| f(t))($dateFromPartsF(_, _, _, _, _, _, _, _))
        case $dateToPartsF(d, t, i) => (f(d) |@| f(t))($dateToPartsF(_, _, i))
      }
  }

  implicit def ops[F[_]: Functor](implicit I: ExprOp3_6F :<: F)
      : ExprOpOps.Aux[ExprOp3_6F, F] =
    new ExprOpOps[ExprOp3_6F] {
      type OUT[A] = F[A]

      val simplify: AlgebraM[Option, ExprOp3_6F, Fix[F]] = κ(None)

      def bson: Algebra[ExprOp3_6F, Bson] = {
        case $mergeObjectsF(d) => Bson.Doc("$mergeObjects" -> Bson.Arr(d: _*))
        case $dateFromStringF(s, t) =>
          Bson.Doc("$dateFromString" -> Bson.Doc(
            "dateString" -> s,
            "timezone" -> t))
        case $dateFromPartsF(y, m, d, h, mi, s, ms, t) =>
          Bson.Doc("$dateFromPartsF" -> Bson.Doc(
            "year" -> y,
            "month" -> m,
            "day" -> d,
            "hour" -> h,
            "minute" -> mi,
            "second" -> s,
            "milliseconds" -> ms,
            "timezone" -> t))
        case $dateToPartsF(d, t, i) =>
          Bson.Doc("$dateToParts" -> Bson.Doc(
            "date" -> d,
            "timezone" -> t,
            "iso8601" -> Bson.Bool(i)))
      }

      def rebase[T](base: T)(implicit T: Recursive.Aux[T, OUT]) = I(_).some

      def rewriteRefs0(applyVar: PartialFunction[DocVar, DocVar]) = κ(None)
    }

  final class fixpoint[T, EX[_]: Functor]
    (embed: EX[T] => T)
    (implicit I: ExprOp3_6F :<: EX) {
    @inline private def convert(expr: ExprOp3_6F[T]): T = embed(I.inj(expr))

    def $mergeObjects(docs: List[T]): T = convert($mergeObjectsF(docs))
    def $dateFromString(s: T, tz: T): T = convert($dateFromStringF(s, tz))
    def $dateFromParts(y: T, m: T, d: T, h: T, mi: T, s: T, ms: T, tz: T): T =
      convert($dateFromPartsF(y, m, d, h, mi, s, ms, tz))
    def $dateToParts(date: T, tz: T, iso8601: Boolean): T =
      convert($dateToPartsF(date, tz, iso8601))
  }
}
