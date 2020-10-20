package me.shadaj.scalapy.interpreter

import me.shadaj.scalapy.interpreter.CPythonInterpreter.{globals, throwErrorIfOccured}

class VariableReference(val variable: String) {
  // For tracing down variable reference creation
  // try {
  //   throw new Exception()
  // } catch {
  //   case e => e.printStackTrace()
  // }

  if (VariableReference.allocatedReferences.nonEmpty) {
    VariableReference.allocatedReferences = (this :: VariableReference.allocatedReferences.head) :: VariableReference.allocatedReferences.tail
  } else if (Platform.isNative) {
    println(s"Warning: the reference $variable was allocated into a global space, which means it will not be garbage collected in Scala Native")
  }

  override def toString(): String = variable

  private var cleaned = false

  def cleanup(): Unit = CPythonInterpreter.withGil {
    if (!cleaned) {
      cleaned = true
      Platform.Zone { implicit zone =>
        CPythonAPI.PyDict_DelItemString(globals, Platform.toCString(variable))
        throwErrorIfOccured()
      }
    }
  }

  override def finalize(): Unit = cleanup()
}

object VariableReference {
  import scala.collection.mutable
  private[scalapy] var allocatedReferences: List[List[VariableReference]] = List.empty
}