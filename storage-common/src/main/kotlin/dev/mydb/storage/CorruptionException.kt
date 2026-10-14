package dev.mydb.storage

/**
 * 체크섬은 통과했지만 내용을 해석할 수 없을 때 던진다.
 * 잘린 쓰기로는 생길 수 없으므로 버전 불일치나 버그를 뜻한다.
 */
class CorruptionException(message: String) : RuntimeException(message)
