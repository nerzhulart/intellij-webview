// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.mac

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.FromNativeContext
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.NativeMapped
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Component
import java.awt.Window
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Stable JNA boundary for the Objective-C runtime used by the macOS WebView implementation. */
internal object MacObjectiveC {
  private val objcLibrary = NativeLibrary.getInstance("objc")
  private val native: MacObjectiveCRuntime = Native.load("Foundation", MacObjectiveCRuntime::class.java)

  private val objcMsgSend: Function = objcLibrary.getFunction("objc_msgSend")
  private val objcMsgSendSuper: Function = objcLibrary.getFunction("objc_msgSendSuper")

  private const val CF_STRING_ENCODING_UTF8 = 0x08000100
  private const val CF_STRING_ENCODING_UTF16_LE = 0x14000100

  fun getObjcClass(name: String): ID = native.objc_getClass(name)

  fun getProtocol(name: String): ID = native.objc_getProtocol(name)

  fun selector(name: String): Pointer = native.sel_registerName(name)

  fun invoke(receiver: ID, selector: String, vararg arguments: Any?): ID {
    if (isNil(receiver)) return ID.NIL
    return ID(objcMsgSend.invokeLong(arrayOf(receiver, selector(selector), *arguments)))
  }

  fun isNil(id: ID?): Boolean = id == null || id.toLong() == 0L

  fun allocateObjcClassPair(superclass: ID, name: String): ID {
    return native.objc_allocateClassPair(superclass, name, 0)
  }

  fun registerObjcClassPair(cls: ID) {
    native.objc_registerClassPair(cls)
  }

  fun addMethod(cls: ID, selector: String, callback: Callback, encoding: String) {
    check(native.class_addMethod(cls, selector(selector), callback, encoding)) {
      "Cannot add Objective-C method $selector"
    }
  }

  fun addProtocol(cls: ID, protocol: ID) {
    check(native.class_addProtocol(cls, protocol)) {
      "Cannot add Objective-C protocol"
    }
  }

  fun invokeSuper(receiver: ID, superclass: ID, selector: String, argument: ID) {
    objcMsgSendSuper.invokeVoid(arrayOf(ObjcSuper(receiver.toLong(), superclass.toLong()), selector(selector), argument))
  }

  fun nsString(value: String?): ID {
    if (value == null) return ID.NIL
    val stringClass = getObjcClass("NSString")
    if (value.isEmpty()) return invoke(stringClass, "string")
    val bytes = value.toByteArray(StandardCharsets.UTF_16LE)
    val memory = Memory(bytes.size.toLong())
    memory.write(0, bytes, 0, bytes.size)
    val encoding = native.CFStringConvertEncodingToNSStringEncoding(CF_STRING_ENCODING_UTF16_LE)
    val allocated = invoke(stringClass, "alloc")
    return invoke(invoke(allocated, "initWithBytes:length:encoding:", memory, bytes.size.toLong(), encoding), "autorelease")
  }

  fun toStringViaUTF8(value: ID): String? {
    if (isNil(value)) return null
    val characterCount = native.CFStringGetLength(value)
    val buffer = ByteArray(characterCount * 3 + 1)
    check(native.CFStringGetCString(value, buffer, buffer.size, CF_STRING_ENCODING_UTF8).toInt() != 0) {
      "Could not convert Objective-C string"
    }
    val length = buffer.indexOf(0).let { if (it >= 0) it else buffer.size }
    return String(buffer, 0, length, StandardCharsets.UTF_8)
  }

  fun windowFromAwt(window: Window): ID {
    return try {
      val awtAccessor = Class.forName("sun.awt.AWTAccessor")
      val componentAccessor = awtAccessor.getMethod("getComponentAccessor").invoke(null)
      val getPeer = componentAccessor.javaClass.getMethod("getPeer", Component::class.java).apply { isAccessible = true }
      val peer = getPeer.invoke(componentAccessor, window) ?: return ID.NIL
      val platformWindow = peer.javaClass.getMethod("getPlatformWindow").invoke(peer) ?: return ID.NIL
      val pointerField = platformWindow.javaClass.superclass.getDeclaredField("ptr").apply { isAccessible = true }
      ID(pointerField.getLong(platformWindow))
    }
    catch (_: ReflectiveOperationException) {
      ID.NIL
    }
  }

  private val mainThreadTasks = ConcurrentHashMap<String, Runnable>()
  private var nextMainThreadTaskId = 0L
  private var mainThreadCallback: Callback? = null

  @Synchronized
  fun executeOnMainThread(block: Runnable) {
    ensureMainThreadAdapter()
    val taskId = (++nextMainThreadTaskId).toString()
    mainThreadTasks[taskId] = block
    val runnable = invoke(invoke(getObjcClass("WebViewIdeaRunnable"), "alloc"), "init")
    val key = invoke(nsString(taskId), "retain")
    invoke(runnable, "performSelectorOnMainThread:withObject:waitUntilDone:", selector("run:"), key, false)
    invoke(runnable, "release")
  }

  private fun ensureMainThreadAdapter() {
    if (mainThreadCallback != null) return
    val cls = allocateObjcClassPair(getObjcClass("NSObject"), "WebViewIdeaRunnable")
    val callback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER")
      fun callback(self: ID, selector: Pointer, key: ID) {
        val taskId = toStringViaUTF8(key)
        invoke(key, "release")
        if (taskId != null) mainThreadTasks.remove(taskId)?.run()
      }
    }
    addMethod(cls, "run:", callback, "v@:@")
    registerObjcClassPair(cls)
    mainThreadCallback = callback
  }
}

private interface MacObjectiveCRuntime : Library {
  fun objc_getClass(name: String): ID
  fun objc_getProtocol(name: String): ID
  fun sel_registerName(name: String): Pointer
  fun objc_allocateClassPair(superclass: ID, name: String, extraBytes: Int): ID
  fun objc_registerClassPair(cls: ID)
  fun class_addMethod(cls: ID, selector: Pointer, callback: Callback, encoding: String): Boolean
  fun class_addProtocol(cls: ID, protocol: ID): Boolean
  fun CFStringGetLength(value: ID): Int
  fun CFStringGetCString(value: ID, buffer: ByteArray, bufferSize: Int, encoding: Int): Byte
  fun CFStringConvertEncodingToNSStringEncoding(encoding: Int): Long
}

@Structure.FieldOrder("receiver", "superclass")
private class ObjcSuper() : Structure() {
  @JvmField var receiver: Long = 0
  @JvmField var superclass: Long = 0

  constructor(receiver: Long, superclass: Long) : this() {
    this.receiver = receiver
    this.superclass = superclass
  }
}

/** Objective-C object pointer or scalar result returned from `objc_msgSend`. */
internal class ObjcId(value: Long = 0L) : NativeLong(value) {
  override fun toByte(): Byte = toLong().toByte()
  override fun toShort(): Short = toLong().toShort()
  override fun toInt(): Int = toLong().toInt()
  override fun toLong(): Long = super.toLong()
  override fun toFloat(): Float = toLong().toFloat()
  override fun toDouble(): Double = toLong().toDouble()

  fun booleanValue(): Boolean = toLong() != 0L

  companion object {
    val NIL = ObjcId()
  }
}

internal typealias ID = ObjcId

@Structure.FieldOrder("origin", "size")
internal class NSRect(
  x: Double = 0.0,
  y: Double = 0.0,
  width: Double = 0.0,
  height: Double = 0.0,
) : Structure(), Structure.ByValue {
  @JvmField var origin = NSPoint(x, y)
  @JvmField var size = NSSize(width, height)
}

@Structure.FieldOrder("x", "y")
internal class NSPoint(x: Double = 0.0, y: Double = 0.0) : Structure(), Structure.ByValue {
  @JvmField var x = CGFloat(x)
  @JvmField var y = CGFloat(y)
}

@Structure.FieldOrder("width", "height")
internal class NSSize(width: Double = 0.0, height: Double = 0.0) : Structure(), Structure.ByValue {
  @JvmField var width = CGFloat(width)
  @JvmField var height = CGFloat(height)
}

internal class CGFloat(private val value: Double = 0.0) : NativeMapped {
  override fun fromNative(nativeValue: Any?, context: FromNativeContext?): Any {
    return CGFloat((nativeValue as Number).toDouble())
  }

  override fun toNative(): Any = if (Native.LONG_SIZE == 4) value.toFloat() else value

  override fun nativeType(): Class<*> = if (Native.LONG_SIZE == 4) Float::class.javaObjectType else Double::class.javaObjectType
}