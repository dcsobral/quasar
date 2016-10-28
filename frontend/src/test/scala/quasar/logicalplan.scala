/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar

import quasar.Predef._
import quasar.fp._

import matryoshka.Fix
import org.scalacheck._
import org.specs2.scalaz._
import scalaz._, Scalaz._
import scalaz.scalacheck.ScalazProperties._
import shapeless.contrib.scalaz.instances._
import pathy.Path._

class LogicalPlanSpecs extends Spec {
  import LogicalPlan._

  implicit val arbLogicalPlan: Arbitrary ~> λ[α => Arbitrary[LogicalPlan[α]]] =
    new (Arbitrary ~> λ[α => Arbitrary[LogicalPlan[α]]]) {
      def apply[α](arb: Arbitrary[α]): Arbitrary[LogicalPlan[α]] =
        Arbitrary {
          Gen.oneOf(readGen[α], addGen(arb), constGen[α], letGen(arb), freeGen[α](Nil))
        }
    }

  // Switch this to parameterize over Funcs as well
  def addGen[A: Arbitrary]: Gen[LogicalPlan[A]] = for {
    l <- Arbitrary.arbitrary[A]
    r <- Arbitrary.arbitrary[A]
  } yield Invoke(std.MathLib.Add, Func.Input2(l, r))

  def letGen[A: Arbitrary]: Gen[LogicalPlan[A]] = for {
    n            <- Gen.choose(0, 1000)
    (form, body) <- Arbitrary.arbitrary[(A, A)]
  } yield Let(Symbol("tmp" + n), form, body)

  def readGen[A]: Gen[LogicalPlan[A]] = Gen.const(Read(rootDir </> file("foo")))

  import DataArbitrary._

  def constGen[A]: Gen[LogicalPlan[A]] = for {
    data <- Arbitrary.arbitrary[Data]
  } yield Constant(data)

  def freeGen[A](vars: List[Symbol]): Gen[LogicalPlan[A]] = for {
    n <- Gen.choose(0, 1000)
  } yield Free(Symbol("tmp" + n))

  implicit val arbIntLP = arbLogicalPlan(Arbitrary.arbInt)

  checkAll(traverse.laws[LogicalPlan])

  import quasar.std.StdLib._, relations._, quasar.std.StdLib.set._, structural._, math._
  import quasar.frontend.fixpoint.lpf

  "normalizeTempNames" should {
    "rename simple nested lets" in {
      LogicalPlan.normalizeTempNames(
        lpf.let('foo, lpf.read(file("foo")),
          lpf.let('bar, lpf.read(file("bar")),
            Fix(MakeObjectN(
              lpf.constant(Data.Str("x")) -> Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("x")))),
              lpf.constant(Data.Str("y")) -> Fix(ObjectProject(lpf.free('bar), lpf.constant(Data.Str("y"))))))))) must_==
        lpf.let('__tmp0, lpf.read(file("foo")),
          lpf.let('__tmp1, lpf.read(file("bar")),
            Fix(MakeObjectN(
              lpf.constant(Data.Str("x")) -> Fix(ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("x")))),
              lpf.constant(Data.Str("y")) -> Fix(ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("y"))))))))
    }

    "rename shadowed name" in {
      LogicalPlan.normalizeTempNames(
        lpf.let('x, lpf.read(file("foo")),
          lpf.let('x, Fix(MakeObjectN(
              lpf.constant(Data.Str("x")) -> Fix(ObjectProject(lpf.free('x), lpf.constant(Data.Str("x")))))),
            lpf.free('x)))) must_==
        lpf.let('__tmp0, lpf.read(file("foo")),
          lpf.let('__tmp1, Fix(MakeObjectN(
              lpf.constant(Data.Str("x")) -> Fix(ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("x")))))),
            lpf.free('__tmp1)))
    }
  }

  "normalizeLets" should {
    "re-nest" in {
      LogicalPlan.normalizeLets(
        lpf.let('bar,
          lpf.let('foo,
            lpf.read(file("foo")),
            Fix(Filter(lpf.free('foo), Fix(Eq(Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("x")))), lpf.constant(Data.Str("z"))))))), 
          Fix(MakeObjectN(
            lpf.constant(Data.Str("y")) -> Fix(ObjectProject(lpf.free('bar), lpf.constant(Data.Str("y")))))))) must_==
        lpf.let('foo,
          lpf.read(file("foo")),
          lpf.let('bar,
            Fix(Filter(lpf.free('foo), Fix(Eq(Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("x")))), lpf.constant(Data.Str("z")))))), 
            Fix(MakeObjectN(
              lpf.constant(Data.Str("y")) -> Fix(ObjectProject(lpf.free('bar), lpf.constant(Data.Str("y"))))))))
    }

    "re-nest deep" in {
      LogicalPlan.normalizeLets(
        lpf.let('baz,
          lpf.let('bar,
            lpf.let('foo,
              lpf.read(file("foo")),
              Fix(Filter(lpf.free('foo), Fix(Eq(Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("x")))), lpf.constant(Data.Int(0))))))),
            Fix(Filter(lpf.free('bar), Fix(Eq(Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("y")))), lpf.constant(Data.Int(1))))))),
          Fix(MakeObjectN(
            lpf.constant(Data.Str("z")) -> Fix(ObjectProject(lpf.free('bar), lpf.constant(Data.Str("z")))))))) must_==
        lpf.let('foo,
          lpf.read(file("foo")),
          lpf.let('bar,
            Fix(Filter(lpf.free('foo), Fix(Eq(Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("x")))), lpf.constant(Data.Int(0)))))),
            lpf.let('baz,
              Fix(Filter(lpf.free('bar), Fix(Eq(Fix(ObjectProject(lpf.free('foo), lpf.constant(Data.Str("y")))), lpf.constant(Data.Int(1)))))),
              Fix(MakeObjectN(
                lpf.constant(Data.Str("z")) -> Fix(ObjectProject(lpf.free('bar), lpf.constant(Data.Str("z")))))))))
    }

    "hoist multiple lpf.lets" in {
      LogicalPlan.normalizeLets(
        Fix(Add(
          lpf.let('x, lpf.constant(Data.Int(0)), Fix(Add(lpf.free('x), lpf.constant(Data.Int(1))))),
          lpf.let('y, lpf.constant(Data.Int(2)), Fix(Add(lpf.free('y), lpf.constant(Data.Int(3)))))))) must_==
        lpf.let('x, lpf.constant(Data.Int(0)),
          lpf.let('y, lpf.constant(Data.Int(2)),
            Fix(Add(
              Fix(Add(lpf.free('x), lpf.constant(Data.Int(1)))),
              Fix(Add(lpf.free('y), lpf.constant(Data.Int(3))))))))
    }

    "hoist deep lpf.let by one level" in {
      LogicalPlan.normalizeLets(
        Fix(Add(
          lpf.constant(Data.Int(0)),
          Fix(Add(
            lpf.let('x, lpf.constant(Data.Int(1)), Fix(Add(lpf.free('x), lpf.constant(Data.Int(2))))),
            lpf.constant(Data.Int(3))))))) must_==
        Fix(Add(
          lpf.constant(Data.Int(0)),
          lpf.let('x, lpf.constant(Data.Int(1)),
            Fix(Add(
              Fix(Add(lpf.free('x), lpf.constant(Data.Int(2)))),
              lpf.constant(Data.Int(3)))))))
    }
  }
}
