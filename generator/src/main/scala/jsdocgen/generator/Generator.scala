package jsdocgen.generator

import java.io.{File, PrintWriter}

import jsdocgen._
import jsdocgen.domain.pickle._
import jsdocgen.domain._

import scala.io.Source

/**
 * Created by marci on 08-11-2015.
 */
object Generator {
  val libPackage = "jsdocgen.lib"
  val unionClass = libPackage + ".Union"
  val unionImplClass = libPackage + ".UnionImpl"

  val reserved = Set(
    "clone",
    "toString"
  )

  val keyword = Set(
    "type"
  )
  def isReserved(name: String) = reserved.contains(name)

  def id(from: String) =
    if (isReserved(from)) from + "_"
    else if (keyword.contains(from)) s"`$from`"
    else from

  def generateFile(
    targetDir: File,
    docletsFile: File,
    rootPackage : Seq[String] = Seq("jsdocgen"),
    utilPackage : String = "pkg",
    implicits : Seq[String] = Seq("implicits")
  ) : Seq[File] = {
    println("reading json: " + docletsFile)
    val doclets = {
      val json = Source.fromFile(docletsFile, "UTF-8").mkString
      read[Seq[Doclet]](json)
    }

    generate(
      targetDir,
      doclets,
      rootPackage,
      utilPackage,
      implicits
    )
  }

  def generate(
    targetDir: File,
    doclets: Seq[Doclet],
    rootPackage : Seq[String] = Seq("jsdocgen"),
    utilPackage : String = "pkg",
    implicits : Seq[String] = Seq("implicits")
  ) : Seq[File] = {

    val namespaces = doclets
      .collect({
        case d : domain.Namespace => d.longname.split('.').toSeq
      }) :+ Seq()

    val functions = doclets
      .collect({
        case d : domain.Function => d
      })

    val classes = doclets
      .collect({
        case d : domain.Class => d
      })

    val typedefs = doclets
      .collect({
        case d : domain.Typedef => d
      })

    val members = doclets
      .collect({
        case d : domain.Member => d
      })

    val packageList = (o:String) =>
      Option(o).map(_.split('.').toSeq).getOrElse(Seq())

    val parentProp = (o:HasParent) =>
      packageList(o.memberof)

    val functionByParent = functions.groupBy(parentProp).withDefaultValue(Seq())
    val classByParent = classes.groupBy(parentProp).withDefaultValue(Seq())
    val typedefByParent = typedefs.groupBy(parentProp).withDefaultValue(Seq())
    val memberByParent = members.groupBy(parentProp).withDefaultValue(Seq())

    val builtins = Map(
      "string" -> "String",
      "number" -> "Double"
    )
    def resolve(name: String) : String =
      builtins
        .get(name)
        .getOrElse("scala.scalajs.js.Any")

    def resolveUnion(t: domain.Type) : Set[String] =
      t.names.map(n => resolve(n)).toSet

    def unionTypeName(names: Set[String]) = names.toSeq.sorted.mkString("`", "|", "`")

    def unionTypeRef(ref: String) = (rootPackage ++ implicits :+ ref).mkString(".")

    def resolveType(name: domain.Type) : String = {
      val types = resolveUnion(name)
      if (types.size > 1)
        unionTypeRef(unionTypeName(types))
      else
        types.toSeq.headOption
          .getOrElse("scala.scalajs.js.Any")
    }

    def resolveReturn(ret: Option[domain.Return]) : String =
      ret
        .map(name => resolveType(name.`type`))
        .getOrElse("Unit")


    def indent(str: String, level: Int) : String = {
      str.split('\n').map(("  " * level) + _).mkString("\n")
    }

    case class Out(out: PrintWriter, level: Int) {
      def write(str: String) = out.println(indent(str, level))
      def nest = copy(level = level+1)
    }


    def writeStatics(nsName: Seq[String], out: Out) : Unit = {
      import out.write

      for {
        fn <- functionByParent(nsName)
      } {
        if (isReserved(fn.name))
          write(s"""@scala.scalajs.js.annotation.JSName("${fn.name}")""")
        write(s"def ${id(fn.name)}(")

        write(
          (for { p <- fn.params } yield {
            s"  ${id(p.name)} : scala.scalajs.js.UndefOr[${resolveType(p.`type`)}] = scala.scalajs.js.undefined"
          }).mkString(",\n")
        )
        write(s") : ${resolveReturn(fn.returns)} = scala.scalajs.js.native")
        write("")
      }

    }

    def writeClass(cl: domain.Class, out: Out) = {
      import out.write

      write(s"@scala.scalajs.js.native")
      write(s"""@scala.scalajs.js.annotation.JSName("${cl.longname}")""")
      write(s"class ${cl.name} extends scala.scalajs.js.Object {")

      if (!cl.params.isEmpty) {
        write("  def this(")
        write(
          cl.params.map({ param =>
            s"    ${id(param.name)} : scala.scalajs.js.Any"
          }).mkString(",\n")
        )
        write("  ) = this()")
      }

      write(s"}")
    }

    def writeTypedef(td: domain.Typedef, out: Out) = {
      import out.write

      write(s"@scala.scalajs.js.native")
      write(s"trait ${td.name} extends scala.scalajs.js.Object {")

      val mems = memberByParent(packageList(td.longname))

      for {
        m <- mems
      } {
        if (isReserved(m.name))
          write(s"""@scala.scalajs.js.annotation.JSName("${m.name}")""")

        write(s"  var ${id(m.name)} : ${resolveType(m.`type`)} = scala.scalajs.js.native")
      }

      write(s"}")

      write(s"object ${td.name} {")
      write(s"  def apply(")
      write(
        (for { m <- mems } yield {
          s"    ${id(m.name)} : scala.scalajs.js.UndefOr[${resolveType(m.`type`)}] = scala.scalajs.js.undefined"
        }).mkString(",\n")
      )
      write(s"  ) = scala.scalajs.js.Dynamic.literal(")
      write(
        (for { m <- mems } yield {
          s"""    "${m.name}" -> ${id(m.name)}"""
        }).mkString(",\n")
      )
      write(s"  ).asInstanceOf[${td.name}]")
      write(s"}")

    }

    def writeImplicits(out: Out): Unit = {
      import out.write

      val unions = for {
        ns <- namespaces
        td <- typedefByParent(ns)
        m <- memberByParent(td.longname.split('.'))
        union = resolveUnion(m.`type`)
        if union.size > 1
      } yield union

      val unionSet = unions.toSet

      for {
        union <- unionSet
      } {
        val name = unionTypeName(union)

        write(s"trait $name extends $unionClass")

        for {
          t <- union
        } {
          write(s"implicit def `$t -> ${name.tail}(v: $t) = new ${unionImplClass}(v) with $name")
          write(s"implicit def `$t -> UndefOr ${name.tail}(v: $t) : scala.scalajs.js.UndefOr[$name] = new ${unionImplClass}(v) with $name")
        }


      }


    }


    def packageJoin(pkg: Seq[String]) =
      (rootPackage ++ pkg).mkString(".")

    def sourceFile(longname: String) = {
      val path = rootPackage ++ longname.split('.')
      val file = new File(targetDir, path.mkString("/") + ".scala")
      file.getParentFile.mkdirs()
      file
    }

    def writeFile(longname: String)(writer: Out => Unit) = {
      val file = sourceFile(longname)
      val pw = new PrintWriter(file)
      writer(Out(pw, 0))
      pw.close()
      file
    }

    val classFiles = for {
      ns <- namespaces
      cl <- classByParent(ns)
    } yield writeFile(cl.longname) { out =>
        out.write(s"package ${packageJoin(ns)}")
        writeClass(cl, out)
    }

    val traitFiles = for {
      ns <- namespaces
      td <- typedefByParent(ns)
    } yield writeFile(td.longname) { out =>
      out.write(s"package ${packageJoin(ns)}")
      writeTypedef(td, out)
    }

    val packageFiles = for {
      ns <- namespaces
    } yield writeFile((ns :+ utilPackage).mkString(".")) { out =>
        import out.write

        write(s"package ${packageJoin(ns)}")
        write("@scala.scalajs.js.native")
        if (ns.isEmpty)
          write(s"object ${utilPackage} extends scala.scalajs.js.GlobalScope {")
        else {
          write(s"""@scala.scalajs.js.annotation.JSName("${ns.mkString(".")}")""")
          write(s"object ${utilPackage} extends scala.scalajs.js.Object {")
        }

        writeStatics(ns, out.nest)

        write(s"}")
        write("")
    }

    val implicitsFile = writeFile(implicits.mkString(".")) { out =>
      import out.write

      write(s"package ${packageJoin(implicits.init)}")
      write(s"package object ${implicits.last} {")

      writeImplicits(out.nest)

      write(s"}")
      write("")
    }

    classFiles ++ traitFiles ++ packageFiles :+ implicitsFile
  }


}

