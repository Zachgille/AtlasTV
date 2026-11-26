package com.atlas.atlastv.xtream.parser

sealed interface XtreamParseResult<out T> {
    data class Success<T>(val value: T) : XtreamParseResult<T>
    data class Failure(val message: String) : XtreamParseResult<Nothing>
}
