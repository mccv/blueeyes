package blueeyes.core.service

import HttpMethods._
import blueeyes.util.Future

trait RestHierarchyBuilder[T] {
  import scala.collection.mutable.{Stack, ArrayBuffer}
  
  private type Handler[T] = (Map[Symbol, String], HttpRequest[T]) => Future[HttpResponse[T]]
  
  private val pathStack: Stack[RestPathPattern] = new Stack[RestPathPattern].push(RestPathPattern.Root);
  private val _hierarchy: ArrayBuffer[(RestPathPattern, HttpMethod, Handler[T])] = new ArrayBuffer
  
  def hierarchy = _hierarchy.toList
  
  def path(path: RestPathPattern)(f: => Unit): Unit = {
    pathStack.push(path)
    
    try { f } finally { pathStack.pop() }
  }
  
  def get(handler: Handler[T]) = custom(GET, handler)
  
  def put(handler: Handler[T]) = custom(PUT, handler)
  
  def post(handler: Handler[T]) = custom(POST, handler)
  
  def delete(handler: Handler[T]) = custom(DELETE, handler)
  
  def options(handler: Handler[T]) = custom(OPTIONS, handler)
  
  def head(handler: Handler[T]) = custom(HEAD, handler)
  
  def connect(handler: Handler[T]) = custom(CONNECT, handler)
  
  def trace(handler: Handler[T]) = custom(TRACE, handler)
  
  def custom(method: HttpMethod, handler: Handler[T]) = {
    _hierarchy += ((currentPath, method, handler))
  }
  
  private def currentPath: RestPathPattern = pathStack.foldRight[RestPathPattern](RestPathPattern.Root) { (path, element) => path ++ element }
}