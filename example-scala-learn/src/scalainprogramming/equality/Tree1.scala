package scalainprogramming.equality

/*
 * Copyright (C) 2007-2008 Artima, Inc. All rights reserved.
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
 *
 * Example code from:
 *
 * Programming in Scala (First Edition, Version 6)
 * by Martin Odersky, Lex Spoon, Bill Venners
 *
 * http://booksites.artima.com/programming_in_scala
 */

object Tree1 {
  trait Tree[+T] {
    def elem: T
    def left: Tree[T]
    def right: Tree[T]
  }
  
  object EmptyTree extends Tree[Nothing] {
    def elem =
      throw new NoSuchElementException("EmptyTree.elem")
    def left =
      throw new NoSuchElementException("EmptyTree.left")
    def right =
      throw new NoSuchElementException("EmptyTree.right")
  }
  
  class Branch[+T](
    val elem: T,
    val left: Tree[T],
    val right: Tree[T]
  ) extends Tree[T]
}
