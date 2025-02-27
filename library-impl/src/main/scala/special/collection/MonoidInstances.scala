package special.collection

import scalan.{NeverInline, Internal}

class MonoidBuilderInst extends MonoidBuilder {
  val intPlusMonoid = new IntPlusMonoid(0)
  val longPlusMonoid = new LongPlusMonoid(0L)

  @Internal
  val intMaxMonoid = new IntMaxMonoid(0x80000000)  // Int.MinValue cannot be used due to virtualization
  @Internal
  val intMinMonoid = new IntMinMonoid(0x7fffffff)  // Int.MinValue cannot be used due to virtualization
  @Internal
  val longMaxMonoid = new LongMaxMonoid(0x8000000000000000L)  // Long.MinValue cannot be used due to virtualization
  @Internal
  val longMinMonoid = new LongMinMonoid(0x7fffffffffffffffL)  // Int.MinValue cannot be used due to virtualization

  @Internal
  override def pairMonoid[@specialized(Int, Long) A, @specialized(Int, Long) B](m1: Monoid[A], m2: Monoid[B]): Monoid[(A, B)] =
    new PairMonoid(m1, m2)
}

class IntPlusMonoid(val zero: Int) extends Monoid[Int] {
  def plus(x: Int, y: Int): Int = java7.compat.Math.addExact(x, y)
  def power(x: Int, n: Int): Int = java7.compat.Math.multiplyExact(x, n)
}

@Internal
class IntMaxMonoid(val zero: Int) extends Monoid[Int] {
  @NeverInline
  def plus(x: Int, y: Int): Int = x max y
  @NeverInline
  def power(x: Int, n: Int): Int = x // x max x max .... x (n times)
}

@Internal
class IntMinMonoid(val zero: Int) extends Monoid[Int] {
  @NeverInline
  def plus(x: Int, y: Int): Int = x min y
  @NeverInline
  def power(x: Int, n: Int): Int = x // x max x max .... x (n times)
}

class LongPlusMonoid(val zero: Long) extends Monoid[Long] {
  def plus(x: Long, y: Long): Long = java7.compat.Math.addExact(x, y)
  def power(x: Long, n: Int): Long = java7.compat.Math.multiplyExact(x, n.toLong)
}

@Internal
class LongMaxMonoid(val zero: Long) extends Monoid[Long] {
  @NeverInline
  def plus(x: Long, y: Long): Long = x max y
  @NeverInline
  def power(x: Long, n: Int): Long = x // x max x max .... x (n times)
}

@Internal
class LongMinMonoid(val zero: Long) extends Monoid[Long] {
  @NeverInline
  def plus(x: Long, y: Long): Long = x min y
  @NeverInline
  def power(x: Long, n: Int): Long = x // x max x max .... x (n times)
}

@Internal
class PairMonoid[@specialized(Int, Long) A, @specialized(Int, Long) B](val m1: Monoid[A], m2: Monoid[B]) extends Monoid[(A,B)] {
  override def zero: (A, B) = (m1.zero, m2.zero)
  override def plus(x: (A, B), y: (A, B)): (A, B) = (m1.plus(x._1, y._1), m2.plus(x._2, y._2))
  override def power(x: (A, B), n: Int): (A, B) = (m1.power(x._1, n), m2.power(x._2, n))
}
