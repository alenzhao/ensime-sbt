// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.util

import java.io._
import java.net.URI
import java.nio.charset.Charset

import org.apache.commons.vfs2.FileObject

import org.ensime.api._
import org.ensime.util.file._

object RichFileObject {
  implicit class RichFileObject(val fo: FileObject) extends AnyVal {
    // None if the fo is not an entry in an archive
    def pathWithinArchive: Option[String] = {
      val uri = fo.getName.getURI
      if (uri.startsWith("jar") || uri.startsWith("zip"))
        Some(fo.getName.getRoot.getRelativeName(fo.getName))
      else None
    }

    // assumes it is a local file
    def asLocalFile: File = {
      require(fo.getName.getURI.startsWith("file"))
      new File(new URI(fo.getName.getURI))
    }
  }
}

object FileUtils {

  implicit def toSourceFileInfo(f: Either[File, SourceFileInfo]): SourceFileInfo =
    f.fold(l => SourceFileInfo(l, None, None), r => r)

  def exists(f: SourceFileInfo) = f match {
    case SourceFileInfo(f, _, _) if f.exists() => true
    case SourceFileInfo(_, Some(c), _) => true
    case SourceFileInfo(_, _, Some(f)) if f.exists() => true
    case _ => false
  }

  // prefer file.readString()
  def readFile(f: File)(implicit cs: Charset): Either[IOException, String] =
    try Right(f.readString()(cs))
    catch {
      case e: IOException => Left(e)
    }

  def writeChanges(changes: List[FileEdit])(implicit cs: Charset): Either[Exception, List[File]] = {
    val editsByFile = changes.collect { case ed: TextEdit => ed }.groupBy(_.file)
    val newFiles = changes.collect { case ed: NewFile => ed }
    try {
      val rewriteList = newFiles.map { ed => (ed.file, ed.text) } ++
        editsByFile.map {
          case (file, fileChanges) =>
            readFile(file)(cs) match {
              case Right(contents) =>
                val newContents = FileEditHelper.applyEdits(fileChanges.toList, contents)
                (file, newContents)
              case Left(e) => throw e
            }
        }

      val deleteFiles = changes.collect { case ed: DeleteFile => ed }
      for (ed <- deleteFiles) {
        ed.file.delete()
      }

      Right(
        for {
          (file, contents) <- rewriteList
        } yield {
          file.writeString(contents)(cs)
          file
        }
      )
    } catch {
      case e: Exception => Left(e)
    }
  }

  def writeDiffChanges(changes: List[FileEdit], renameFromTo: Option[(File, File)] = None)(implicit cs: Charset): Either[Exception, File] = {
    //sorted for the sake of testing, because otherwise the order of files in diff is not well defined
    val editsByFile = scala.collection.immutable.SortedMap(changes.groupBy(_.file).toSeq: _*)
    try {
      val diffContents =
        editsByFile.map {
          case (file, (nf @ NewFile(newFile, _, _, _)) :: Nil) =>
            val Some((from, to)) = renameFromTo
            readFile(from)(cs) match {
              case Right(contents) =>
                val edits = editsByFile(from).collect { case ed: TextEdit => ed }
                FileEditHelper.diffFromNewFile(nf, edits, contents)
              case Left(e) => throw e
            }
          case (file, fileChanges) =>
            val textEdits = fileChanges.collect { case ed: TextEdit => ed }
            val deleteFile = fileChanges.collectFirst { case delete: DeleteFile => delete }
            readFile(file)(cs) match {
              case Right(contents) =>
                deleteFile match {
                  //ignore text changes if the file is being deleted
                  case Some(deletion) => FileEditHelper.diffFromDeleteFile(deletion, contents)
                  case None => FileEditHelper.diffFromTextEdits(textEdits, contents, file, file)
                }
              case Left(e) => throw e
            }
        }.mkString("\n")

      Right({
        val diffFile = java.io.File.createTempFile("ensime-diff-", ".tmp").canon
        diffFile.writeString(diffContents)(cs)
        diffFile
      })
    } catch {
      case e: Exception => Left(e)
    }
  }

}
