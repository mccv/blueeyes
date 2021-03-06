package blueeyes.core.service

import scala.xml.NodeSeq

import blueeyes.util.Future
import blueeyes.json.JsonAST._
import blueeyes.core.data.Bijection
import blueeyes.core.http._
import blueeyes.core.http.HttpHeaders._
import blueeyes.core.http.HttpHeaderImplicits._

trait HttpRequestHandlerCombinators {
  /** The path combinator creates a handler that is defined only for suffixes 
   * of the specified path pattern.
   *
   * {{{
   * path("/foo") {
   *   ...
   * }
   * }}}
   */
  def path[T, S](path: RestPathPattern) = (h: HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = path.isDefinedAt(r.subpath) && h.isDefinedAt(path.shift(r))
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val pathParameters = path(r.subpath)
      
      val shiftedRequest = path.shift(r)
      
      h(shiftedRequest.copy(parameters = shiftedRequest.parameters ++ pathParameters))
    }
  }
  
  /** Yields the remaining path to the specified function, which should return 
   * a request handler.
   * {{{
   * remainingPath { path =>
   *   get {
   *     ... 
   *   }
   * }
   * }}}
   */
  def remainingPath[T, S](handler: String => HttpRequestHandler2[T, S]) = path(RestPathPattern.Root `...` ('remainingPath)) { 
    parameter(IdentifierWithDefault('remainingPath, () => "")) {
      handler
    }
  }
  
  /** The method combinator creates a handler that is defined only for the 
   * specified HTTP method.
   */
  def method[T, S](method: HttpMethod) = (h: HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = r.method == method
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = r.method match {
      case `method` => h(r)
      
      case _ => error("The handler " + h + " can only respond to HTTP method " + method)
    }
  }
  
  /**
   * <pre>
   * path("/foo") {
   *   ...
   * } ~ orFail { request => BadRequest -> "The path " + request.path + " is malformed" }
   * </pre>
   */
  def orFail[T, S](h: HttpRequest[T] => (HttpFailure, String)): HttpRequestHandler2[T, S] = new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = true
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val fail = h(r)
      
      Future.dead(HttpException(fail._1, fail._2))
    }
  }
  
  /**
   * <pre>
   * path("/foo") {
   *   ...
   * } ~ orFail("The path is malformed")
   * </pre>
   */
  def orFail[T, S](msg: String): HttpRequestHandler2[T, S] = orFail { request => HttpStatusCodes.BadRequest -> msg }
  
  /** The path end combinator creates a handler that is defined only for paths 
   * that are fully matched.
   */
  def $ [T, S](h: HttpRequestHandler2[T, S]): HttpRequestHandler2[T, S] = path(RestPathPatternParsers.EmptyPathPattern) { h }
  
  /** Forces a particular combinator to match.
   * <pre>
   * commit(r => BadRequest -> "Bad path: " + r.path) {
   *   path("/foo") {
   *     ...
   *   }
   * }
   * </pre>
   */
  def commit[T, S](msgGen: HttpRequest[T] => (HttpFailure, String))(h: HttpRequestHandler2[T, S]): HttpRequestHandler2[T, S] = new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = true
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      if (!h.isDefinedAt(r)) {
        val (statusCode, reason) = msgGen(r)
        
        Future.dead(HttpException(statusCode, reason))
      }
      else h.apply(r)
    }
  }
  
  /** Converts a full request handler into a partial request handler that 
   * handles every input. Note: This is an implicit and will automatically
   * convert all full request handlers into partial request handlers,
   * as required by type signatures.
   */
  implicit def commit[T, S](h: HttpRequest[T] => Future[HttpResponse[S]]): HttpRequestHandler2[T, S] = new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = true
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      h.apply(r)
    }
  }
  
  /** Attemps to peek to see if a particular handler will handle a request. 
   * Used to convert a fast-failing handler into a skipping one.
   * <pre>
   * justTry {
   *   path("/foo") {
   *     ...
   *   }
   * }
   * </pre>
   */
  def justTry[T, S](h: HttpRequestHandler2[T, S]): HttpRequestHandler2[T, S] = new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = {
      try {
        h.isDefinedAt(r)
      }
      catch {
        case _ => false
      }
    }
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = h.apply(r)
  }
  
  def get     [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.GET)      { commit { h } } }
  def put     [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.PUT)      { commit { h } } }
  def post    [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.POST)     { commit { h } } }
  def delete  [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.DELETE)   { commit { h } } }
  def head    [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.HEAD)     { commit { h } } }
  def patch   [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.PATCH)    { commit { h } } }
  def options [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.OPTIONS)  { commit { h } } }
  def trace   [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.TRACE)    { commit { h } } }
  def connect [T, S](h: HttpRequestHandlerFull2[T, S]): HttpRequestHandler2[T, S] = $ { method(HttpMethods.CONNECT)  { commit { h } } }

  /**
   * Extracts data from the request. The extractor combinators can be used to 
   * factor out extraction logic that's duplicated across a range of handlers.
   * <p>
   * Extractors are fail-fast combinators. If they cannot extract the required
   * information during evaluation of isDefinedAt() method, they immediately
   * throw an HttpException. 
   * <pre>
   * extract(_.parameters('username)) { username =>
   *   ...
   * }
   * </pre>
   */
  def extract[T, S, E1](extractor: HttpRequest[T] => E1) = (h: E1 => HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = {
      try {
        val extracted = extractor(r)
        
        h(extracted).isDefinedAt(r)
      }
      catch {
        case t: Throwable => throw HttpException(HttpStatusCodes.BadRequest, t)
      }
    }
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val extracted = extractor(r)
      
      h(extracted).apply(r)
    }
  }
  
  /**
   * <pre>
   * extract2(r => (r.parameters('username), r.parameters('password))) { (username, password) =>
   *   ...
   * }
   * </pre>
   */
  def extract2[T, S, E1, E2](extractor: HttpRequest[T] => (E1, E2)) = (h: (E1, E2) => HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = {
      try {
        val extracted = extractor(r)
        
        h(extracted._1, extracted._2).isDefinedAt(r)
      }
      catch {
        case t: Throwable => throw HttpException(HttpStatusCodes.BadRequest, t)
      }
    }
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val extracted = extractor(r)
      
      h(extracted._1, extracted._2).apply(r)
    }
  }
  
  def extract3[T, S, E1, E2, E3](extractor: HttpRequest[T] => (E1, E2, E3)) = (h: (E1, E2, E3) => HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = {
      try {
        val extracted = extractor(r)
        
        h(extracted._1, extracted._2, extracted._3).isDefinedAt(r)
      }
      catch {
        case t: Throwable => throw HttpException(HttpStatusCodes.BadRequest, t)
      }
    }
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val extracted = extractor(r)
      
      h(extracted._1, extracted._2, extracted._3).apply(r)
    }
  }
  
  def extract4[T, S, E1, E2, E3, E4](extractor: HttpRequest[T] => (E1, E2, E3, E4)) = (h: (E1, E2, E3, E4) => HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = {
      try {
        val extracted = extractor(r)
        
        h(extracted._1, extracted._2, extracted._3, extracted._4).isDefinedAt(r)
      }
      catch {
        case t: Throwable => throw HttpException(HttpStatusCodes.BadRequest, t)
      }
    }
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val extracted = extractor(r)
      
      h(extracted._1, extracted._2, extracted._3, extracted._4).apply(r)
    }
  }
  
  def extract5[T, S, E1, E2, E3, E4, E5](extractor: HttpRequest[T] => (E1, E2, E3, E4, E5)) = (h: (E1, E2, E3, E4, E5) => HttpRequestHandler2[T, S]) => new HttpRequestHandler2[T, S] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = {
      try {
        val extracted = extractor(r)
        
        h(extracted._1, extracted._2, extracted._3, extracted._4, extracted._5).isDefinedAt(r)
      }
      catch {
        case t: Throwable => throw HttpException(HttpStatusCodes.BadRequest, t)
      }
    }
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[S]] = {
      val extracted = extractor(r)
      
      h(extracted._1, extracted._2, extracted._3, extracted._4, extracted._5).apply(r)
    }
  }
  
  /** A special-case extractor for parameters.
   * <pre>
   * parameter('token) { token =>
   *   get {
   *     ...
   *   }
   * }
   * </pre>
   */
  def parameter[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String]) = (h: String => HttpRequestHandler2[T, S]) => extract[T, S, String] { request =>
    request.parameters.get(s1AndDefault.identifier).getOrElse(s1AndDefault.default)
  } { h }

  /** A special-case extractor for parameters.
   * <pre>
   * parameters2('username, 'password) { (username, password) =>
   *   get {
   *     ...
   *   }
   * }
   * </pre>
   */  
  def parameters2[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String) => HttpRequestHandler2[T, S]) => extract2[T, S, String, String] { request =>
    (request.parameters.get(s1AndDefault.identifier).getOrElse(s1AndDefault.default), request.parameters.get(s2AndDefault.identifier).getOrElse(s2AndDefault.default))
  } { h }
  
  def parameters3[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String], s3AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String, String) => HttpRequestHandler2[T, S]) => extract3[T, S, String, String, String] { request =>
    (request.parameters.get(s1AndDefault.identifier).getOrElse(s1AndDefault.default), request.parameters.get(s2AndDefault.identifier).getOrElse(s2AndDefault.default), request.parameters.get(s3AndDefault.identifier).getOrElse(s3AndDefault.default))
  } { h }
  
  def parameters4[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String], s3AndDefault: IdentifierWithDefault[Symbol, String], s4AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String, String, String) => HttpRequestHandler2[T, S]) => extract4[T, S, String, String, String, String] { request =>
    (request.parameters.get(s1AndDefault.identifier).getOrElse(s1AndDefault.default), request.parameters.get(s2AndDefault.identifier).getOrElse(s2AndDefault.default), request.parameters.get(s3AndDefault.identifier).getOrElse(s3AndDefault.default), request.parameters.get(s4AndDefault.identifier).getOrElse(s4AndDefault.default))
  } { h }

  def parameters5[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String], s3AndDefault: IdentifierWithDefault[Symbol, String], s4AndDefault: IdentifierWithDefault[Symbol, String], s5AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String, String, String, String) => HttpRequestHandler2[T, S]) => extract5[T, S, String, String, String, String, String] { request =>
    (request.parameters.get(s1AndDefault.identifier).getOrElse(s1AndDefault.default), request.parameters.get(s2AndDefault.identifier).getOrElse(s2AndDefault.default), request.parameters.get(s3AndDefault.identifier).getOrElse(s3AndDefault.default), request.parameters.get(s4AndDefault.identifier).getOrElse(s4AndDefault.default), request.parameters.get(s5AndDefault.identifier).getOrElse(s5AndDefault.default))
  } { h }

  private def extractCookie[T](request: HttpRequest[T], s: Symbol, defaultValue: Option[String] = None) = {
    def cookies = (for (HttpHeaders.Cookie(value) <- request.headers) yield HttpHeaders.Cookie(value)).headOption.getOrElse(HttpHeaders.Cookie(Nil))
    cookies.cookies.find(_.name == s.name).map(_.cookieValue).orElse(defaultValue).getOrElse(error("Expected cookie " + s.name))
  }

  /** A special-case extractor for cookie.
   * <pre>
   * cookie('token) { token =>
   *   get {
   *     ...
   *   }
   * }
   * </pre>
   */
  def cookie[T, S](s1: Symbol)(h: String => HttpRequestHandler2[T, S]): HttpRequestHandler2[T, S] = extract[T, S, String] { request =>
    extractCookie(request, s1)
  } { h }

  /** A special-case extractor for cookie supporting a default value.
   * <pre>
   * cookie('token, "defaultValue") { token =>
   *   get {
   *     ...
   *   }
   * }
   * </pre>
   */
  def cookie[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String])(h: String => HttpRequestHandler2[T, S]): HttpRequestHandler2[T, S] = extract[T, S, String] { request =>
    extractCookie(request, s1AndDefault.identifier, Some(s1AndDefault.default))
  } { h }

  /** A special-case extractor for cookies.
   * <pre>
   * cookies2('username, 'password) { (username, password) =>
   *   get {
   *     ...
   *   }
   * }
   * </pre>
   */
  def cookies2[T, S](s1: Symbol, s2: Symbol) = (h: (String, String) => HttpRequestHandler2[T, S]) => extract2[T, S, String, String] { request =>
    (extractCookie(request, s1), extractCookie(request, s2))
  } { h }

  def cookies2[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String) => HttpRequestHandler2[T, S]) => extract2[T, S, String, String] { request =>
    (extractCookie(request, s1AndDefault.identifier, Some(s1AndDefault.default)), extractCookie(request, s2AndDefault.identifier, Some(s2AndDefault.default)))
  } { h }

  def cookies3[T, S](s1: Symbol, s2: Symbol, s3: Symbol) = (h: (String, String, String) => HttpRequestHandler2[T, S]) => extract3[T, S, String, String, String] { request =>
    (extractCookie(request, s1), extractCookie(request, s2), extractCookie(request, s3))
  } { h }

  def cookies3[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String], s3AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String, String) => HttpRequestHandler2[T, S]) => extract3[T, S, String, String, String] { request =>
    (extractCookie(request, s1AndDefault.identifier, Some(s1AndDefault.default)), extractCookie(request, s2AndDefault.identifier, Some(s2AndDefault.default)), extractCookie(request, s3AndDefault.identifier, Some(s3AndDefault.default)))
  } { h }

  def cookies4[T, S](s1: Symbol, s2: Symbol, s3: Symbol, s4: Symbol) = (h: (String, String, String, String) => HttpRequestHandler2[T, S]) => extract4[T, S, String, String, String, String] { request =>
    (extractCookie(request, s1), extractCookie(request, s2), extractCookie(request, s3), extractCookie(request, s4))
  } { h }

  def cookies4[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String], s3AndDefault: IdentifierWithDefault[Symbol, String], s4AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String, String, String) => HttpRequestHandler2[T, S]) => extract4[T, S, String, String, String, String] { request =>
    (extractCookie(request, s1AndDefault.identifier, Some(s1AndDefault.default)), extractCookie(request, s2AndDefault.identifier, Some(s2AndDefault.default)), extractCookie(request, s3AndDefault.identifier, Some(s3AndDefault.default)), extractCookie(request, s4AndDefault.identifier, Some(s4AndDefault.default)))
  } { h }

  def cookies5[T, S](s1: Symbol, s2: Symbol, s3: Symbol, s4: Symbol, s5: Symbol) = (h: (String, String, String, String, String) => HttpRequestHandler2[T, S]) => extract5[T, S, String, String, String, String, String] { request =>
    (extractCookie(request, s1), extractCookie(request, s2), extractCookie(request, s3), extractCookie(request, s4), extractCookie(request, s5))
  } { h }

  def cookies5[T, S](s1AndDefault: IdentifierWithDefault[Symbol, String], s2AndDefault: IdentifierWithDefault[Symbol, String], s3AndDefault: IdentifierWithDefault[Symbol, String], s4AndDefault: IdentifierWithDefault[Symbol, String], s5AndDefault: IdentifierWithDefault[Symbol, String]) = (h: (String, String, String, String, String) => HttpRequestHandler2[T, S]) => extract5[T, S, String, String, String, String, String] { request =>
    (extractCookie(request, s1AndDefault.identifier, Some(s1AndDefault.default)), extractCookie(request, s2AndDefault.identifier, Some(s2AndDefault.default)), extractCookie(request, s3AndDefault.identifier, Some(s3AndDefault.default)), extractCookie(request, s4AndDefault.identifier, Some(s5AndDefault.default)), extractCookie(request, s4AndDefault.identifier, Some(s5AndDefault.default)))
  } { h }

  private def extractField[F <: JValue](content: JValue, s1AndDefault: IdentifierWithDefault[Symbol, F])(implicit mc: Manifest[F]): F = {
    val c: Class[F] = mc.erasure.asInstanceOf[Class[F]]

    ((content \ s1AndDefault.identifier.name) -->? c).getOrElse(s1AndDefault.default).asInstanceOf[F]
  }

  private def fieldError[F <: JValue](s: Symbol, mc: Manifest[F])(): F  = error("Expected field " + s.name + " to be " + mc.erasure.asInstanceOf[Class[F]].getName)

  def field[S, F1 <: JValue](s1AndDefault: IdentifierWithDefault[Symbol, F1])(implicit mc1: Manifest[F1]) = (h: F1 => HttpRequestHandler2[JValue, S]) => {
    val c1: Class[F1] = mc1.erasure.asInstanceOf[Class[F1]]
    
    extract[JValue, S, F1] { (request: HttpRequest[JValue]) =>
      val content = request.content.getOrElse(error("Expected request body to be JSON object"))
      
      extractField(content, s1AndDefault)
    } (h)
  }
  
  //def field[S, F1 <: JValue](s1: Symbol)(implicit mc1: Manifest[F1]) = (h: F1 => HttpRequestHandler2[JValue, S]) => field(IdentifierWithDefault(s1, fieldError(s1, mc1) _))(mc1)(h)

  def fields2[S, F1 <: JValue, F2 <: JValue](s1AndDefault: IdentifierWithDefault[Symbol, F1], s2AndDefault: IdentifierWithDefault[Symbol, F2])(implicit mc1: Manifest[F1], mc2: Manifest[F2]) = (h: (F1, F2) => HttpRequestHandler2[JValue, S]) => {
    extract2 { (request: HttpRequest[JValue]) =>
      val content = request.content.getOrElse(error("Expected request body to be JSON object"))
      
      (
        extractField(content, s1AndDefault)(mc1),
        extractField(content, s2AndDefault)(mc2)
      )
    } (h)
  }
  
  def fields3[S, F1 <: JValue, F2 <: JValue, F3 <: JValue](s1AndDefault: IdentifierWithDefault[Symbol, F1], s2AndDefault: IdentifierWithDefault[Symbol, F2], s3AndDefault: IdentifierWithDefault[Symbol, F3])(implicit mc1: Manifest[F1], mc2: Manifest[F2], mc3: Manifest[F3]) = (h: (F1, F2, F3) => HttpRequestHandler2[JValue, S]) => {
    extract3 { (request: HttpRequest[JValue]) =>
      val content = request.content.getOrElse(error("Expected request body to be JSON object"))
      
      (
        extractField(content, s1AndDefault)(mc1),
        extractField(content, s2AndDefault)(mc2),
        extractField(content, s3AndDefault)(mc3)
      )
    } (h)
  }
  
  def fields4[S, F1 <: JValue, F2 <: JValue, F3 <: JValue, F4 <: JValue](s1AndDefault: IdentifierWithDefault[Symbol, F1], s2AndDefault: IdentifierWithDefault[Symbol, F2], s3AndDefault: IdentifierWithDefault[Symbol, F3], s4AndDefault: IdentifierWithDefault[Symbol, F4])(implicit mc1: Manifest[F1], mc2: Manifest[F2], mc3: Manifest[F3], mc4: Manifest[F4]) = (h: (F1, F2, F3, F4) => HttpRequestHandler2[JValue, S]) => {
    extract4 { (request: HttpRequest[JValue]) =>
      val content = request.content.getOrElse(error("Expected request body to be JSON object"))
      
      (
        extractField(content, s1AndDefault)(mc1),
        extractField(content, s2AndDefault)(mc2),
        extractField(content, s3AndDefault)(mc3),
        extractField(content, s4AndDefault)(mc4)
      )
    } (h)
  }
 
  def fields5[S, F1 <: JValue, F2 <: JValue, F3 <: JValue, F4 <: JValue, F5 <: JValue](s1AndDefault: IdentifierWithDefault[Symbol, F1], s2AndDefault: IdentifierWithDefault[Symbol, F2], s3AndDefault: IdentifierWithDefault[Symbol, F3], s4AndDefault: IdentifierWithDefault[Symbol, F4], s5AndDefault: IdentifierWithDefault[Symbol, F5])(implicit mc1: Manifest[F1], mc2: Manifest[F2], mc3: Manifest[F3], mc4: Manifest[F4], mc5: Manifest[F5]) = (h: (F1, F2, F3, F4, F5) => HttpRequestHandler2[JValue, S]) => {
    extract5 { (request: HttpRequest[JValue]) =>
      val content = request.content.getOrElse(error("Expected request body to be JSON object"))
      
      (
        extractField(content, s1AndDefault)(mc1),
        extractField(content, s2AndDefault)(mc2),
        extractField(content, s3AndDefault)(mc3),
        extractField(content, s4AndDefault)(mc4),
        extractField(content, s5AndDefault)(mc5)
      )
    } (h)
  }
  
  /** The accept combinator creates a handler that is defined only for requests
   * that have the specified content type. Requires an implicit bijection
   * used for transcoding.
   */
  def accept[T, S, U](mimeType: MimeType)(h: HttpRequestHandler2[T, S])(implicit b: Bijection[U, T]): HttpRequestHandler2[U, S] = new HttpRequestHandler2[U, S] {
    def isDefinedAt(r: HttpRequest[U]): Boolean = {
      val requestMimeType: List[MimeType] = (for (`Content-Type`(mimeTypes) <- r.headers) yield mimeTypes.toList).toList.flatten
      
      requestMimeType.find(_ == mimeType).map { mimeType =>
        h.isDefinedAt(r.copy(content = r.content.map(b.apply)))
      }.orElse {
        r.content.map(b.isDefinedAt _)
      }.getOrElse(false)
    }
    
    def apply(r: HttpRequest[U]) = h(r.copy(content = r.content.map(b.apply)))
  }
  
  /** The produce combinator creates a handler that is produces responses 
   * that have the specified content type. Requires an implicit bijection
   * used for transcoding.
   */
  def produce[T, S, V](mimeType: MimeType)(h: HttpRequestHandler2[T, S])(implicit b: Bijection[S, V]): HttpRequestHandler2[T, V] = new HttpRequestHandler2[T, V] {
    def isDefinedAt(r: HttpRequest[T]): Boolean = h.isDefinedAt(r)
    
    def apply(r: HttpRequest[T]): Future[HttpResponse[V]] = h(r).map { response =>
      response.copy(content = response.content.map(b.apply), headers = response.headers + `Content-Type`(mimeType))
    }
  }
  
  /** The content type combinator creates a handler that accepts and produces
   * requests and responses of the specified content type. Requires an implicit
   * bijection used for transcoding.
   */
  def contentType[T, S](mimeType: MimeType)(h: HttpRequestHandler[T])(implicit b1: Bijection[S, T]): HttpRequestHandler[S] = {
    implicit val b2 = b1.inverse
    
    accept(mimeType) {
      produce(mimeType) {
        h
      }
    }
  }
  
  
  /** The json combinator creates a handler that accepts and produces JSON. 
   * Requires an implicit bijection used for transcoding.
   */
  def jvalue[T](h: HttpRequestHandler[JValue])(implicit b: Bijection[T, JValue]): HttpRequestHandler[T] = contentType(MimeTypes.application/MimeTypes.json) { h }
  
  /** The xml combinator creates a handler that accepts and produces XML. 
   * Requires an implicit bijection used for transcoding.
   */
  def xml[T](h: HttpRequestHandler[NodeSeq])(implicit b: Bijection[T, NodeSeq]): HttpRequestHandler[T] = contentType(MimeTypes.text/MimeTypes.xml) { h }
}


case class IdentifierWithDefault[T, S](identifier: T, default_ : () => S) {
  def default = default_ ()
}