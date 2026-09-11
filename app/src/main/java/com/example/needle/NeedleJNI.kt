package com.example.needle

public class NeedleException : Exception {
    constructor(message: String) : super(message)
}

public class NeedleJNI {

    companion object {
        init {
            System.loadLibrary("needle2jni")
        }

        @JvmStatic
        external fun load(modelBytes: ByteArray): Int

        @JvmStatic
        external fun init(
            systemPrompt: String?,
            toolsJson: String?,
            toolIndexPath: String?
        ): Int

        @JvmStatic
        external fun complete(
            input: String,
            maxNewTokens: Int
        ): String

        @JvmStatic
        external fun reset()
    }
}