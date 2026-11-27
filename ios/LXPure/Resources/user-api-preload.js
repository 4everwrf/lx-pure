/*
 * Derived from lyswhut/lx-music-mobile (https://github.com/lyswhut/lx-music-mobile)
 * Licensed under the Apache License, Version 2.0 (the "License").
 * See the LICENSE and NOTICE files in the repository root for full terms.
 *
 * Adapted for the JavaScriptCore native bridge used by LX Pure iOS (https://github.com/4everwrf/lx-pure).
 */
'use strict'

globalThis.lx_setup = (key, id, name, description, version, author, homepage, rawScript) => {
  delete globalThis.lx_setup
  const _nativeCall = globalThis.__lx_native_call__
  delete globalThis.__lx_native_call__
  const checkLength = (str, length = 1048576) => {
    if (typeof str == 'string' && str.length > length) throw new Error('Input too long')
    return str
  }
  const nativeFuncNames = [
    '__lx_native_call__set_timeout',
    '__lx_native_call__clear_timeout',
    '__lx_native_call__utils_str2b64',
    '__lx_native_call__utils_b642buf',
    '__lx_native_call__utils_str2md5',
    '__lx_native_call__utils_aes_encrypt',
    '__lx_native_call__utils_rsa_encrypt',
    '__lx_native_call__utils_zlib_inflate',
    '__lx_native_call__utils_zlib_deflate',
  ]
  const nativeFuncs = {}
  for (const fnName of nativeFuncNames) {
    const nativeFunc = globalThis[fnName]
    delete globalThis[fnName]
    if (typeof nativeFunc === 'function') {
      nativeFuncs[fnName.replace('__lx_native_call__', '')] = (...args) => {
        for (const arg of args) checkLength(arg)
        return nativeFunc(...args)
      }
    }
  }

  const KEY_PREFIX = {
    publicKeyStart: '-----BEGIN PUBLIC KEY-----',
    publicKeyEnd: '-----END PUBLIC KEY-----',
    privateKeyStart: '-----BEGIN PRIVATE KEY-----',
    privateKeyEnd: '-----END PRIVATE KEY-----',
  }
  const RSA_PADDING = {
    OAEPWithSHA1AndMGF1Padding: 'RSA/ECB/OAEPWithSHA1AndMGF1Padding',
    NoPadding: 'RSA/ECB/NoPadding',
  }
  const AES_MODE = {
    CBC_128_PKCS7Padding: 'AES/CBC/PKCS7Padding',
    ECB_128_NoPadding: 'AES',
  }
  const nativeCall = (action, data) => {
    data = JSON.stringify(data)
    checkLength(data, 2097152)
    _nativeCall(key, action, data)
  }

  const callbacks = new Map()
  let timeoutId = 0
  const _setTimeout = (callback, timeout = 0, ...params) => {
    if (typeof callback !== 'function') throw new Error('callback required a function')
    if (typeof timeout !== 'number' || timeout < 0) throw new Error('timeout required a number')
    if (timeoutId > 90000000000) throw new Error('max timeout')
    const tid = timeoutId++
    callbacks.set(tid, {
      callback(...args) {
        callback(...args)
      },
      params,
    })
    nativeFuncs.set_timeout(tid, parseInt(timeout))
    return tid
  }
  const _clearTimeout = (tid) => {
    const target = callbacks.get(tid)
    if (!target) return
    callbacks.delete(tid)
    if (nativeFuncs.clear_timeout) {
      nativeFuncs.clear_timeout(tid)
    }
  }
  const handleSetTimeout = (tid) => {
    const target = callbacks.get(tid)
    if (!target) return
    callbacks.delete(tid)
    target.callback(...target.params)
  }

  const B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

  function toUint8Array(buf) {
    if (buf instanceof Uint8Array) return buf
    if (ArrayBuffer.isView(buf)) return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength)
    if (buf instanceof ArrayBuffer) return new Uint8Array(buf)
    if (Array.isArray(buf)) return new Uint8Array(buf)
    return null
  }

  function bytesToBase64(bytes) {
    let out = ''
    const len = bytes.length
    for (let i = 0; i < len; i += 3) {
      const b0 = bytes[i] & 0xFF
      const has1 = i + 1 < len
      const has2 = i + 2 < len
      const b1 = has1 ? (bytes[i + 1] & 0xFF) : 0
      const b2 = has2 ? (bytes[i + 2] & 0xFF) : 0
      const trip = (b0 << 16) | (b1 << 8) | b2
      out += B64_CHARS[(trip >> 18) & 63]
      out += B64_CHARS[(trip >> 12) & 63]
      out += has1 ? B64_CHARS[(trip >> 6) & 63] : '='
      out += has2 ? B64_CHARS[trip & 63] : '='
    }
    return out
  }

  function bytesToLatin1(bytes) {
    let out = ''
    const chunkSize = 8192
    for (let i = 0; i < bytes.length; i += chunkSize) {
      const end = Math.min(i + chunkSize, bytes.length)
      const slice = new Array(end - i)
      for (let j = i; j < end; j++) slice[j - i] = bytes[j] & 0xFF
      out += String.fromCharCode.apply(null, slice)
    }
    return out
  }

  // Decode UTF-8 byte sequence to UTF-16 string (supports 1..4 byte sequences & surrogate pairs)
  function bytesToString(bytes) {
    let result = ''
    let i = 0
    const len = bytes.length
    while (i < len) {
      const b0 = bytes[i] & 0xFF
      if (b0 < 0x80) {
        result += String.fromCharCode(b0)
        i += 1
      } else if ((b0 & 0xE0) === 0xC0 && i + 1 < len) {
        const b1 = bytes[i + 1] & 0xFF
        result += String.fromCharCode(((b0 & 0x1F) << 6) | (b1 & 0x3F))
        i += 2
      } else if ((b0 & 0xF0) === 0xE0 && i + 2 < len) {
        const b1 = bytes[i + 1] & 0xFF
        const b2 = bytes[i + 2] & 0xFF
        result += String.fromCharCode(((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F))
        i += 3
      } else if ((b0 & 0xF8) === 0xF0 && i + 3 < len) {
        const b1 = bytes[i + 1] & 0xFF
        const b2 = bytes[i + 2] & 0xFF
        const b3 = bytes[i + 3] & 0xFF
        const cp = (((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)) - 0x10000
        if (cp >= 0 && cp <= 0xFFFFF) {
          result += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF))
        } else {
          result += '\uFFFD'
        }
        i += 4
      } else {
        result += '\uFFFD'
        i += 1
      }
    }
    return result
  }

  // Encode UTF-16 string to UTF-8 byte array (supports surrogate pairs -> 4-byte UTF-8)
  function stringToBytes(inputString) {
    const bytes = []
    for (let i = 0; i < inputString.length; i++) {
      let cp = inputString.charCodeAt(i)
      if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < inputString.length) {
        const low = inputString.charCodeAt(i + 1)
        if (low >= 0xDC00 && low <= 0xDFFF) {
          cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00)
          i += 1
        }
      }
      if (cp < 0x80) {
        bytes.push(cp)
      } else if (cp < 0x800) {
        bytes.push((cp >> 6) | 0xC0)
        bytes.push((cp & 0x3F) | 0x80)
      } else if (cp < 0x10000) {
        bytes.push((cp >> 12) | 0xE0)
        bytes.push(((cp >> 6) & 0x3F) | 0x80)
        bytes.push((cp & 0x3F) | 0x80)
      } else {
        bytes.push((cp >> 18) | 0xF0)
        bytes.push(((cp >> 12) & 0x3F) | 0x80)
        bytes.push(((cp >> 6) & 0x3F) | 0x80)
        bytes.push((cp & 0x3F) | 0x80)
      }
    }
    return bytes
  }

  const NATIVE_EVENTS_NAMES = {
    init: 'init',
    showUpdateAlert: 'showUpdateAlert',
    request: 'request',
    cancelRequest: 'cancelRequest',
    response: 'response',
  }
  const EVENT_NAMES = {
    request: 'request',
    inited: 'inited',
    updateAlert: 'updateAlert',
  }
  const eventNames = Object.values(EVENT_NAMES)
  const events = {
    request: null,
  }
  const allSources = ['kw', 'kg', 'tx', 'wy', 'mg', 'soda', 'local']
  const supportQualitys = {
    kw: ['128k', '320k', 'flac', 'flac24bit'],
    kg: ['128k', '320k', 'flac', 'flac24bit'],
    tx: ['128k', '320k', 'flac', 'flac24bit'],
    wy: ['128k', '320k', 'flac', 'flac24bit'],
    mg: ['128k', '320k', 'flac', 'flac24bit'],
    soda: ['128k', '320k', 'flac', 'flac24bit'],
    local: [],
  }
  const supportActions = {
    kw: ['musicUrl'],
    kg: ['musicUrl'],
    tx: ['musicUrl'],
    wy: ['musicUrl'],
    mg: ['musicUrl'],
    xm: ['musicUrl'],
    soda: ['musicUrl'],
    local: ['musicUrl', 'lyric', 'pic'],
  }

  const verifyLyricInfo = (info) => {
    if (!info || typeof info != 'object' || typeof info.lyric != 'string') throw new Error('failed')
    if (info.lyric.length > 51200) throw new Error('failed')
    return {
      lyric: info.lyric,
      tlyric: (typeof info.tlyric == 'string' && info.tlyric.length < 5120) ? info.tlyric : null,
      rlyric: (typeof info.rlyric == 'string' && info.rlyric.length < 5120) ? info.rlyric : null,
      lxlyric: (typeof info.lxlyric == 'string' && info.lxlyric.length < 8192) ? info.lxlyric : null,
    }
  }

  const formatErrorMessage = (err) => {
    if (err && typeof err === 'object' && typeof err.message === 'string' && err.message.length > 0) {
      return err.message
    }
    if (typeof err === 'string' && err.length > 0) return err
    return err != null ? String(err) : 'failed'
  }

  const requestQueue = new Map()
  let isInitedApi = false
  let isShowedUpdateAlert = false

  const sendNativeRequest = (url, options, callback) => {
    const requestKey = `${Date.now()}_${Math.random().toString(36).slice(2)}`
    const requestInfo = {
      aborted: false,
      abort: () => {
        if (requestInfo.aborted) return
        requestInfo.aborted = true
        requestQueue.delete(requestKey)
        nativeCall(NATIVE_EVENTS_NAMES.cancelRequest, requestKey)
      },
    }
    requestQueue.set(requestKey, {
      callback,
      requestInfo,
    })

    try {
      nativeCall(NATIVE_EVENTS_NAMES.request, { requestKey, url, options })
    } catch (err) {
      requestQueue.delete(requestKey)
      requestInfo.aborted = true
      if (typeof callback === 'function') {
        callback(err instanceof Error ? err : new Error(formatErrorMessage(err)), null)
      }
    }
    return requestInfo
  }

  const handleNativeResponse = ({ requestKey, error, response }) => {
    const targetRequest = requestQueue.get(requestKey)
    if (!targetRequest) return
    requestQueue.delete(requestKey)
    targetRequest.requestInfo.aborted = true
    if (error == null) {
      if (response && response.binary === true) {
        if (typeof response.body === 'string') {
          response.body = utils.buffer.from(response.body, 'base64')
        } else if (Array.isArray(response.body)) {
          response.body = new Uint8Array(response.body)
        }
      }
      targetRequest.callback(null, response)
    } else {
      targetRequest.callback(new Error(error), null)
    }
  }

  const handleRequest = ({ requestKey, data }) => {
    if (!events.request) return nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: false, errorMessage: 'Request event is not defined' })
    try {
      Promise.resolve().then(() => events.request.call(globalThis.lx, { source: data.source, action: data.action, info: data.info })).then(response => {
        let result
        switch (data.action) {
          case 'musicUrl': {
            const trimmedUrl = typeof response == 'string' ? response.trim() : ''
            if (!trimmedUrl || trimmedUrl.length > 2048 || !/^https?:/i.test(trimmedUrl)) throw new Error('failed')
            result = {
              source: data.source,
              action: data.action,
              data: {
                type: data.info.type,
                url: trimmedUrl,
              },
            }
            break
          }
          case 'lyric':
            result = {
              source: data.source,
              action: data.action,
              data: verifyLyricInfo(response),
            }
            break
          case 'pic': {
            const trimmedPic = typeof response == 'string' ? response.trim() : ''
            if (!trimmedPic || trimmedPic.length > 2048 || !/^https?:/i.test(trimmedPic)) throw new Error('failed')
            result = {
              source: data.source,
              action: data.action,
              data: trimmedPic,
            }
            break
          }
        }
        nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: true, result })
      }).catch(err => {
        nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: false, errorMessage: formatErrorMessage(err) })
      })
    } catch (err) {
      nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: false, errorMessage: formatErrorMessage(err) })
    }
  }

  const jsCall = (action, data) => {
    switch (action) {
      case '__run_error__':
        if (!isInitedApi) isInitedApi = true
        return
      case '__set_timeout__':
        handleSetTimeout(data)
        return
      case 'request':
        handleRequest(data)
        return
      case 'response':
        handleNativeResponse(data)
        return
    }
    return 'Unknown action: ' + action
  }

  Object.defineProperty(globalThis, '__lx_native__', {
    enumerable: false,
    configurable: false,
    writable: false,
    value: (_key, action, data) => {
      if (key != _key) return 'Invalid key'
      return data == null ? jsCall(action) : jsCall(action, JSON.parse(data))
    },
  })

  const handleInit = (info) => {
    if (!info || typeof info !== 'object') {
      nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: 'Missing required parameter init info' })
      return
    }
    if (info.status === false) {
      const msg = typeof info.message === 'string' && info.message.length > 0
        ? info.message.substring(0, 200)
        : 'Init failed'
      nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: msg })
      return
    }
    if (!info.sources || typeof info.sources !== 'object') {
      const msg = typeof info.message === 'string' && info.message.length > 0
        ? info.message.substring(0, 200)
        : 'Missing sources in init info'
      nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: msg })
      return
    }
    const sourceInfo = {
      sources: {},
    }
    try {
      for (const source of allSources) {
        const userSource = info.sources[source]
        if (!userSource || userSource.type !== 'music') continue
        const qualitys = supportQualitys[source]
        const actions = supportActions[source]
        const userActions = Array.isArray(userSource.actions) ? userSource.actions : []
        const userQualitys = Array.isArray(userSource.qualitys) ? userSource.qualitys : []
        sourceInfo.sources[source] = {
          type: 'music',
          actions: actions.filter(a => userActions.includes(a)),
          qualitys: qualitys.filter(q => userQualitys.includes(q)),
        }
      }
    } catch (error) {
      nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: formatErrorMessage(error) })
      return
    }
    nativeCall(NATIVE_EVENTS_NAMES.init, { info: sourceInfo, status: true })
  }

  const handleShowUpdateAlert = (data, resolve, reject) => {
    if (!data || typeof data != 'object') return reject(new Error('parameter format error.'))
    if (!data.log || typeof data.log != 'string') return reject(new Error('log is required.'))
    if (data.updateUrl && (!/^https?:\/\/[^\s$.?#].[^\s]*$/.test(data.updateUrl) || data.updateUrl.length > 1024)) delete data.updateUrl
    if (data.log.length > 1024) data.log = data.log.substring(0, 1024) + '...'
    nativeCall(NATIVE_EVENTS_NAMES.showUpdateAlert, { log: data.log, updateUrl: data.updateUrl, name })
    resolve()
  }

  const dataToB64 = (data) => {
    if (typeof data === 'string') return nativeFuncs.utils_str2b64(data)
    const u8 = toUint8Array(data)
    if (u8) return bytesToBase64(u8)
    throw new Error('data type error: ' + typeof data + ' raw data: ' + data)
  }

  const utils = {
    crypto: {
      aesEncrypt(buffer, mode, key, iv) {
        switch (mode) {
          case 'aes-128-cbc':
            return utils.buffer.from(nativeFuncs.utils_aes_encrypt(dataToB64(buffer), dataToB64(key), iv == null ? '' : dataToB64(iv), AES_MODE.CBC_128_PKCS7Padding), 'base64')
          case 'aes-128-ecb':
            return utils.buffer.from(nativeFuncs.utils_aes_encrypt(dataToB64(buffer), dataToB64(key), '', AES_MODE.ECB_128_NoPadding), 'base64')
          default:
            throw new Error('Unsupported AES mode: ' + mode)
        }
      },
      rsaEncrypt(buffer, key) {
        if (typeof key !== 'string') throw new Error('Invalid RSA key')
        key = key.replace(KEY_PREFIX.publicKeyStart, '')
          .replace(KEY_PREFIX.publicKeyEnd, '')
        return utils.buffer.from(nativeFuncs.utils_rsa_encrypt(dataToB64(buffer), key, RSA_PADDING.NoPadding), 'base64')
      },
      randomBytes(size) {
        const byteArray = new Uint8Array(size)
        for (let i = 0; i < size; i++) {
          byteArray[i] = Math.floor(Math.random() * 256)
        }
        return byteArray
      },
      md5(str) {
        if (typeof str !== 'string') throw new Error('param required a string')
        return nativeFuncs.utils_str2md5(encodeURIComponent(str))
      },
    },
    buffer: {
      from(input, encoding) {
        if (typeof input === 'string') {
          switch (encoding) {
            case 'binary':
            case 'latin1': {
              const out = new Uint8Array(input.length)
              for (let i = 0; i < input.length; i++) out[i] = input.charCodeAt(i) & 0xFF
              return out
            }
            case 'base64':
              return new Uint8Array(JSON.parse(nativeFuncs.utils_b642buf(input)))
            case 'hex': {
              const clean = input.trim()
              if (!clean) return new Uint8Array(0)
              return new Uint8Array((clean.match(/.{1,2}/g) || []).map(byte => parseInt(byte, 16)))
            }
            default:
              return new Uint8Array(stringToBytes(input))
          }
        }
        const u8 = toUint8Array(input)
        if (u8) return new Uint8Array(u8)
        throw new Error('Unsupported input type: ' + input + ' encoding: ' + encoding)
      },
      bufToString(buf, format) {
        const u8 = toUint8Array(buf)
        if (!u8) {
          throw new Error('Input is not a valid buffer: ' + buf + ' format: ' + format)
        }
        switch (format) {
          case 'binary':
          case 'latin1':
            return bytesToLatin1(u8)
          case 'hex': {
            let hex = ''
            for (let i = 0; i < u8.length; i++) {
              hex += (u8[i] & 0xFF).toString(16).padStart(2, '0')
            }
            return hex
          }
          case 'base64':
            return bytesToBase64(u8)
          case 'utf8':
          case 'utf-8':
          default:
            return bytesToString(u8)
        }
      },
    },
    zlib: {
      inflate(buf) {
        return new Promise((resolve, reject) => {
          if (!nativeFuncs.utils_zlib_inflate) {
            return reject(new Error('zlib.inflate is not supported'))
          }
          try {
            const b64 = dataToB64(buf)
            const outB64 = nativeFuncs.utils_zlib_inflate(b64)
            if (!outB64 && b64.length > 0) return reject(new Error('zlib inflate failed'))
            resolve(utils.buffer.from(outB64, 'base64'))
          } catch (err) {
            reject(err instanceof Error ? err : new Error(formatErrorMessage(err)))
          }
        })
      },
      deflate(data) {
        return new Promise((resolve, reject) => {
          if (!nativeFuncs.utils_zlib_deflate) {
            return reject(new Error('zlib.deflate is not supported'))
          }
          try {
            const b64 = dataToB64(data)
            const outB64 = nativeFuncs.utils_zlib_deflate(b64)
            if (!outB64 && b64.length > 0) return reject(new Error('zlib deflate failed'))
            resolve(utils.buffer.from(outB64, 'base64'))
          } catch (err) {
            reject(err instanceof Error ? err : new Error(formatErrorMessage(err)))
          }
        })
      },
    },
  }

  if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = class TextEncoder {
      encode(str = '') {
        return new Uint8Array(stringToBytes(String(str)))
      }
    }
  }
  if (typeof globalThis.TextDecoder === 'undefined') {
    globalThis.TextDecoder = class TextDecoder {
      decode(input) {
        if (input == null) return ''
        const u8 = toUint8Array(input)
        return u8 ? bytesToString(u8) : ''
      }
    }
  }

  globalThis.lx = {
    EVENT_NAMES,
    request(url, { method = 'get', timeout, headers, body, form, formData, binary, follow_max } = {}, callback) {
      const options = { headers, binary: binary === true }
      if (timeout && typeof timeout == 'number' && timeout > 0) options.timeout = Math.min(timeout, 60_000)
      if (typeof follow_max == 'number' && follow_max >= 0) options.follow_max = Math.floor(follow_max)

      let request = sendNativeRequest(url, { method, body, form, formData, ...options }, (err, resp) => {
        if (typeof callback !== 'function') return
        if (err) {
          callback(err, null, null)
        } else {
          callback(err, {
            statusCode: resp.statusCode,
            statusMessage: resp.statusMessage,
            headers: resp.headers,
            body: resp.body,
            finalUrl: resp.finalUrl,
          }, resp.body)
        }
      })

      return () => {
        if (request && !request.aborted) request.abort()
        request = null
      }
    },
    send(eventName, data) {
      return new Promise((resolve, reject) => {
        if (!eventNames.includes(eventName)) return reject(new Error('The event is not supported: ' + eventName))
        switch (eventName) {
          case EVENT_NAMES.inited:
            if (isInitedApi) return reject(new Error('Script is inited'))
            isInitedApi = true
            handleInit(data)
            resolve()
            break
          case EVENT_NAMES.updateAlert:
            if (isShowedUpdateAlert) return reject(new Error('The update alert can only be called once.'))
            isShowedUpdateAlert = true
            handleShowUpdateAlert(data, resolve, reject)
            break
          default:
            reject(new Error('Unknown event name: ' + eventName))
        }
      })
    },
    on(eventName, handler) {
      if (!eventNames.includes(eventName)) return Promise.reject(new Error('The event is not supported: ' + eventName))
      switch (eventName) {
        case EVENT_NAMES.request:
          events.request = handler
          break
        default: return Promise.reject(new Error('The event is not supported: ' + eventName))
      }
      return Promise.resolve()
    },
    utils,
    currentScriptInfo: {
      name,
      description,
      version,
      author,
      homepage,
      rawScript,
    },
    version: '2.0.0',
    env: 'mobile',
  }

  globalThis.setTimeout = _setTimeout
  globalThis.clearTimeout = _clearTimeout

  const freezeObject = (obj) => {
    if (typeof obj != 'object' || obj === null) return
    Object.freeze(obj)
    for (const subObj of Object.values(obj)) freezeObject(subObj)
  }
  freezeObject(globalThis.lx)

  const _toString = Function.prototype.toString
  Function.prototype.toString = function() {
    const desc = Object.getOwnPropertyDescriptor(this, 'name')
    return (desc && desc.configurable)
      ? _toString.apply(this)
      : `function ${this.name || ''}() { [native code] }`
  }
  globalThis.eval = function() {
    throw new Error('eval is not available')
  }
  const proxyFunctionConstructor = new Proxy(Function.prototype.constructor, {
    apply() {
      throw new Error('Dynamic code execution is not allowed.')
    },
    construct() {
      throw new Error('Dynamic code execution is not allowed.')
    },
  })
  Object.defineProperty(Function.prototype, 'constructor', {
    value: proxyFunctionConstructor,
    writable: false,
    configurable: false,
    enumerable: false,
  })
  globalThis.Function = proxyFunctionConstructor

  const excludes = [
    Function.prototype.toString,
    Function.prototype.toLocaleString,
    Object.prototype.toString,
  ]
  const freezeObjectProperty = (obj, freezedObj = new Set()) => {
    if (obj == null) return
    switch (typeof obj) {
      case 'object':
      case 'function':
        if (freezedObj.has(obj)) return
        freezedObj.add(obj)
        for (const [propName, { ...config }] of Object.entries(Object.getOwnPropertyDescriptors(obj))) {
          if (!excludes.includes(config.value)) {
            if (config.writable) config.writable = false
            if (config.configurable) config.configurable = false
            try {
              Object.defineProperty(obj, propName, config)
            } catch (_) {
              // Ignore non-redefinable host exotic properties in JavaScriptCore
            }
          }
          freezeObjectProperty(config.value, freezedObj)
        }
    }
  }
  freezeObjectProperty(globalThis)

  if (typeof console !== 'undefined' && typeof console.log === 'function') {
    console.log('Preload finished.')
  }
}
