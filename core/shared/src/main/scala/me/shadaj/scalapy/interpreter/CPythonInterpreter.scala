package me.shadaj.scalapy.interpreter

import java.{util => ju}

import me.shadaj.scalapy.py.PythonException
import me.shadaj.scalapy.py.IndexError

object CPythonInterpreter {
  CPythonAPI.Py_Initialize()

  private[scalapy] val globals: Platform.Pointer = CPythonAPI.PyDict_New()
  CPythonAPI.Py_IncRef(globals)

  private val builtins = CPythonAPI.PyEval_GetBuiltins()
  Platform.Zone { implicit zone =>
    CPythonAPI.PyDict_SetItemString(globals, Platform.toCString("__builtins__"), builtins)
    throwErrorIfOccured()
  }

  private[scalapy] val falseValue = PyValue.fromNew(CPythonAPI.PyBool_FromLong(Platform.intToCLong(0)), true)
  private[scalapy] val trueValue = PyValue.fromNew(CPythonAPI.PyBool_FromLong(Platform.intToCLong(1)), true)

  private[scalapy] val noneValue: PyValue = PyValue.fromNew(CPythonAPI.Py_BuildValue(Platform.emptyCString), true)

  private val liveWrappedValues = new ju.IdentityHashMap[AnyRef, PointerBox]
  private val reverseLiveWrappedValues = new ju.HashMap[Long, AnyRef]

  val (doNotFreeMeOtherwiseJNAFuncPtrBreaks, cleanupFunctionPointer) = Platform.getFnPtr2 { (self, args) =>
    val id = CPythonAPI.PyLong_AsLongLong(CPythonAPI.PyTuple_GetItem(args, Platform.intToCLong(0)))
    val pointedTo = reverseLiveWrappedValues.remove(id)
    liveWrappedValues.remove(pointedTo)

    CPythonAPI.Py_IncRef(noneValue.underlying)
    noneValue.underlying
  }

  val emptyStrPtr = Platform.alloc(1)
  Platform.setPtrByte(emptyStrPtr, 0, 0)

  val cleanupLambdaMethodDef = Platform.alloc(Platform.ptrSize + Platform.ptrSize + 4 + Platform.ptrSize)
  Platform.setPtrLong(cleanupLambdaMethodDef, 0, Platform.pointerToLong(emptyStrPtr)) // ml_name
  Platform.setPtrLong(cleanupLambdaMethodDef, Platform.ptrSize, Platform.pointerToLong(cleanupFunctionPointer)) // ml_meth
  Platform.setPtrInt(cleanupLambdaMethodDef, Platform.ptrSize + Platform.ptrSize, 0x0001) // ml_flags (https://github.com/python/cpython/blob/master/Include/methodobject.h)
  Platform.setPtrLong(cleanupLambdaMethodDef, Platform.ptrSize + Platform.ptrSize + 4, Platform.pointerToLong(emptyStrPtr)) // ml_doc
  val pyCleanupLambda = PyValue.fromNew(CPythonAPI.PyCFunction_New(cleanupLambdaMethodDef, noneValue.underlying), safeGlobal = true)
  throwErrorIfOccured()

  val weakRefModule = PyValue.fromNew(Platform.Zone { implicit zone =>
    CPythonAPI.PyImport_ImportModule(Platform.toCString("weakref"))
  }, safeGlobal = true)

  val typesModule = PyValue.fromNew(Platform.Zone { implicit zone =>
    CPythonAPI.PyImport_ImportModule(Platform.toCString("types"))
  }, safeGlobal = true)

  val trackerClass = call(typesModule, "new_class", Seq(valueFromString("tracker")))
  throwErrorIfOccured()

  // must be decrefed after being sent to Python
  def wrapIntoPyObject(value: AnyRef): PyValue = withGil {
    if (liveWrappedValues.containsKey(value)) {
      val underlying = liveWrappedValues.get(value).ptr
      CPythonAPI.Py_IncRef(underlying)
      PyValue.fromNew(underlying)
    } else {
      CPythonAPI.Py_IncRef(trackerClass.underlying)
      val trackingPtr = runCallableAndDecref(trackerClass.underlying, Seq())

      val id = Platform.pointerToLong(trackingPtr.underlying)

      liveWrappedValues.put(value, new PointerBox(trackingPtr.underlying))
      reverseLiveWrappedValues.put(id, value)

      call(weakRefModule, "finalize", Seq(trackingPtr, pyCleanupLambda, valueFromLong(id)))
      throwErrorIfOccured()

      trackingPtr
    }
  }

  // lambda wrapper
  val (doNotFreeMeOtherwiseJNAFuncPtrBreaks2, lambdaFunctionPointer) = Platform.getFnPtr2 { (self, args) =>
    val id = Platform.pointerToLong(self)
    val pointedTo = reverseLiveWrappedValues.get(id).asInstanceOf[PyValue => PyValue]

    try {
      val res = pointedTo(PyValue.fromBorrowed(args))
      CPythonAPI.Py_IncRef(res.underlying)
      res.underlying
    } catch {
      case e: IndexError =>
        val exception = selectGlobal("IndexError")
        Platform.Zone { implicit zone =>
          CPythonAPI.PyErr_SetString(exception.underlying,
            Platform.toCString(e.message)
          )
        }
        null
      case e: Throwable =>
        val exception = selectGlobal("RuntimeError")
        Platform.Zone { implicit zone =>
          CPythonAPI.PyErr_SetString(exception.underlying,
            Platform.toCString(e.getMessage())
          )
        }
        null
    }
  }

  val lambdaMethodDef = Platform.alloc(Platform.ptrSize + Platform.ptrSize + 4 + Platform.ptrSize)
  Platform.setPtrLong(lambdaMethodDef, 0, Platform.pointerToLong(emptyStrPtr)) // ml_name
  Platform.setPtrLong(lambdaMethodDef, Platform.ptrSize, Platform.pointerToLong(lambdaFunctionPointer)) // ml_meth
  Platform.setPtrInt(lambdaMethodDef, Platform.ptrSize + Platform.ptrSize, 0x0001) // ml_flags (https://github.com/python/cpython/blob/master/Include/methodobject.h)
  Platform.setPtrLong(lambdaMethodDef, Platform.ptrSize + Platform.ptrSize + 4, Platform.pointerToLong(emptyStrPtr)) // ml_doc

  Platform.Zone { implicit zone =>
    CPythonAPI.PyRun_String(
      Platform.toCString(
        """import collections.abc
          |class SequenceProxy(collections.abc.Sequence):
          |  def __init__(self, len_fn, get_fn):
          |    self.len_fn = len_fn
          |    self.get_fn = get_fn
          |  def __len__(self):
          |    return self.len_fn()
          |  def __getitem__(self, idx):
          |    return self.get_fn(idx)""".stripMargin
      ),
      257,
      globals,
      globals
    )

    throwErrorIfOccured()
  }

  CPythonAPI.PyEval_SaveThread() // release the lock created by Py_Initialize

  @inline private[scalapy] def withGil[T](fn: => T): T = {
    val handle = CPythonAPI.PyGILState_Ensure()

    try {
      fn
    } finally {
      CPythonAPI.PyGILState_Release(handle)
    }
  }

  def eval(code: String): Unit = {
    Platform.Zone { implicit zone =>
      val Py_single_input = 256
      withGil {
        CPythonAPI.PyRun_String(Platform.toCString(code), Py_single_input, globals, globals)
        throwErrorIfOccured()
      }
    }
  }

  def execManyLines(code: String): Unit = {
    Platform.Zone { implicit zone =>
      withGil {
        CPythonAPI.PyRun_String(
          Platform.toCString(code),
          257,
          globals,
          globals
        )

        throwErrorIfOccured()
      }
    }
  }

  def set(variable: String, value: PyValue): Unit = {
    Platform.Zone { implicit zone =>
      withGil {
        CPythonAPI.Py_IncRef(value.underlying)
        CPythonAPI.PyDict_SetItemString(globals, Platform.toCString(variable), value.underlying)
        throwErrorIfOccured()
      }
    }
  }

  private var counter = 0
  def getVariableReference(value: PyValue): VariableReference = {
    val variableName = synchronized {
      val ret = "spy_o_" + counter
      counter += 1
      ret
    }

    Platform.Zone { implicit zone =>
      withGil {
        CPythonAPI.PyDict_SetItemString(globals, Platform.toCString(variableName), value.underlying)
        throwErrorIfOccured()
      }
    }

    new VariableReference(variableName)
  }

  def valueFromBoolean(b: Boolean): PyValue = if (b) trueValue else falseValue
  def valueFromLong(long: Long): PyValue = withGil(PyValue.fromNew(CPythonAPI.PyLong_FromLongLong(long)))
  def valueFromDouble(v: Double): PyValue = withGil(PyValue.fromNew(CPythonAPI.PyFloat_FromDouble(v)))
  def valueFromString(v: String): PyValue = PyValue.fromNew(toNewString(v))

  // Hack to patch around Scala Native not letting us auto-box pointers
  private class PointerBox(val ptr: Platform.Pointer)

  private def toNewString(v: String) = {
    (Platform.Zone { implicit zone =>
      withGil(new PointerBox(CPythonAPI.PyUnicode_FromString(
        Platform.toCString(v, java.nio.charset.Charset.forName("UTF-8"))
      )))
    }).ptr
  }

  def createListCopy[T](seq: Seq[T], elemConv: T => PyValue): PyValue = {
    withGil {
      val retPtr = CPythonAPI.PyList_New(seq.size)
      seq.zipWithIndex.foreach { case (v, i) =>
        val converted = elemConv(v)
        CPythonAPI.Py_IncRef(converted.underlying) // SetItem steals reference
        CPythonAPI.PyList_SetItem(retPtr, Platform.intToCLong(i), converted.underlying)
      }

      PyValue.fromNew(retPtr)
    }
  }

  val seqProxyClass = selectGlobal("SequenceProxy")
  def createListProxy[T](seq: Seq[T], elemConv: T => PyValue): PyValue = {
    call(seqProxyClass, Seq(
      createLambda0(() => valueFromLong(seq.size)),
      createLambda1(idx => {
        val index = idx.getLong.toInt
        if (index < seq.size) {
          elemConv(seq(index))
        } else {
          throw new IndexError(s"Scala sequence proxy index out of range: $index")
        }
      })
    ))
  }

  def createTuple(seq: Seq[PyValue]): PyValue = {
    withGil {
      val retPtr = CPythonAPI.PyTuple_New(seq.size)
      seq.zipWithIndex.foreach { case (v, i) =>
        CPythonAPI.Py_IncRef(v.underlying) // SetItem steals reference
        CPythonAPI.PyTuple_SetItem(retPtr, Platform.intToCLong(i), v.underlying)
      }

      PyValue.fromNew(retPtr)
    }
  }

  def createLambda0(fn: () => PyValue): PyValue = {
    val handlerFnPtr = (args: PyValue) => fn.apply()

    withGil {
      PyValue.fromNew(CPythonAPI.PyCFunction_New(lambdaMethodDef, wrapIntoPyObject(handlerFnPtr).underlying))
    }
  }

  def createLambda1(fn: PyValue => PyValue): PyValue = {
    val handlerFnPtr = (args: PyValue) => {
      fn.apply(args.getTuple(0))
    }

    withGil {
      PyValue.fromNew(CPythonAPI.PyCFunction_New(lambdaMethodDef, wrapIntoPyObject(handlerFnPtr).underlying))
    }
  }

  def createLambda2(fn: (PyValue, PyValue) => PyValue): PyValue = {
    val handlerFnPtr = (args: PyValue) => {
      fn.apply(args.getTuple(0), args.getTuple(1))
    }

    withGil {
      PyValue.fromNew(CPythonAPI.PyCFunction_New(lambdaMethodDef, wrapIntoPyObject(handlerFnPtr).underlying))
    }
  }

  def createLambda3(fn: (PyValue, PyValue, PyValue) => PyValue): PyValue = {
    val handlerFnPtr = (args: PyValue) => {
      fn.apply(args.getTuple(0), args.getTuple(1), args.getTuple(2))
    }

    withGil {
      PyValue.fromNew(CPythonAPI.PyCFunction_New(lambdaMethodDef, wrapIntoPyObject(handlerFnPtr).underlying))
    }
  }

  private def pointerPointerToString(pointer: Platform.PointerToPointer) = withGil {
    Platform.fromCString(CPythonAPI.PyUnicode_AsUTF8(
      CPythonAPI.PyObject_Str(
        Platform.dereferencePointerToPointer(pointer)
      )
    ), java.nio.charset.Charset.forName("UTF-8"))
  }

  def throwErrorIfOccured() = {
    if (Platform.pointerToLong(CPythonAPI.PyErr_Occurred()) != 0) {
      Platform.Zone { implicit zone =>
        val pType = Platform.allocPointerToPointer
        val pValue = Platform.allocPointerToPointer
        val pTraceback = Platform.allocPointerToPointer

        withGil(CPythonAPI.PyErr_Fetch(pType, pValue, pTraceback))

        val pTypeStringified = pointerPointerToString(pType)

        val pValueObject = Platform.dereferencePointerToPointer(pValue)
        val pValueStringified = if (pValueObject != null) {
          " " + pointerPointerToString(pValue)
        } else ""

        throw new PythonException(pTypeStringified + pValueStringified)
      }
    }
  }

  def load(code: String): PyValue = {
    Platform.Zone { implicit zone =>
      val Py_eval_input = 258
      withGil {
        val result = CPythonAPI.PyRun_String(Platform.toCString(code), Py_eval_input, globals, globals)
        throwErrorIfOccured()

        PyValue.fromNew(result)
      }
    }
  }

  def unaryNeg(a: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_Negative(
      a.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  def unaryPos(a: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_Positive(
      a.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  def binaryAdd(a: PyValue, b: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_Add(
      a.underlying,
      b.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  def binarySub(a: PyValue, b: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_Subtract(
      a.underlying,
      b.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  def binaryMul(a: PyValue, b: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_Multiply(
      a.underlying,
      b.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  def binaryDiv(a: PyValue, b: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_TrueDivide(
      a.underlying,
      b.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  def binaryMod(a: PyValue, b: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyNumber_Remainder(
      a.underlying,
      b.underlying
    )

    throwErrorIfOccured()

    PyValue.fromNew(ret)
  }

  private def runCallableAndDecref(callable: Platform.Pointer, args: Seq[PyValue]): PyValue = withGil {
    val result = CPythonAPI.PyObject_Call(
      callable,
      createTuple(args).underlying,
      null
    )

    CPythonAPI.Py_DecRef(callable)

    throwErrorIfOccured()

    PyValue.fromNew(result)
  }

  def callGlobal(method: String, args: Seq[PyValue]): PyValue = {
    Platform.Zone { implicit zone =>
      withGil {
        val methodString = toNewString(method)
        var callable = CPythonAPI.PyDict_GetItemWithError(globals, methodString)
        if (callable == null) {
          CPythonAPI.PyErr_Clear()
          callable = CPythonAPI.PyDict_GetItemWithError(builtins, methodString)
        }

        CPythonAPI.Py_IncRef(callable)
        CPythonAPI.Py_DecRef(methodString)

        throwErrorIfOccured()

        runCallableAndDecref(callable, args)
      }
    }
  }

  def call(on: PyValue, method: String, args: Seq[PyValue]): PyValue = {
    Platform.Zone { implicit zone =>
      withGil {
        val callable = CPythonAPI.PyObject_GetAttrString(on.underlying, Platform.toCString(method))
        throwErrorIfOccured()

        runCallableAndDecref(callable, args)
      }
    }
  }

  def call(callable: PyValue, args: Seq[PyValue]): PyValue = {
    Platform.Zone { implicit zone =>
      withGil {
        throwErrorIfOccured()

        CPythonAPI.Py_IncRef(callable.underlying)
        runCallableAndDecref(callable.underlying, args)
      }
    }
  }

  def selectGlobal(name: String): PyValue = {
    Platform.Zone { implicit zone =>
      val nameString = toNewString(name)

      withGil {
        var gottenValue = CPythonAPI.PyDict_GetItemWithError(globals, nameString)
        if (gottenValue == null) {
          CPythonAPI.PyErr_Clear()
          gottenValue = CPythonAPI.PyDict_GetItemWithError(builtins, nameString)
        }

        CPythonAPI.Py_DecRef(nameString)

        throwErrorIfOccured()

        PyValue.fromNew(gottenValue)
      }
    }
  }

  def select(on: PyValue, value: String, safeGlobal: Boolean = false): PyValue = {
    val valueString = toNewString(value)

    withGil {
      val underlying = CPythonAPI.PyObject_GetAttr(
        on.underlying,
        valueString
      )

      CPythonAPI.Py_DecRef(valueString)

      throwErrorIfOccured()

      PyValue.fromNew(underlying, safeGlobal)
    }
  }

  def update(on: PyValue, value: String, newValue: PyValue): Unit = {
    val valueString = toNewString(value)

    withGil {
      CPythonAPI.PyObject_SetAttr(
        on.underlying,
        valueString,
        newValue.underlying
      )

      CPythonAPI.Py_DecRef(valueString)

      throwErrorIfOccured()
    }
  }

  def selectBracket(on: PyValue, key: PyValue): PyValue = withGil {
    val ret = CPythonAPI.PyObject_GetItem(
      on.underlying,
      key.underlying
    )

    throwErrorIfOccured()

    PyValue.fromBorrowed(ret)
  }

  def updateBracket(on: PyValue, key: PyValue, newValue: PyValue): Unit = {
    withGil {
      CPythonAPI.PyObject_SetItem(
        on.underlying,
        key.underlying,
        newValue.underlying
      )

      throwErrorIfOccured()
    }
  }
}

