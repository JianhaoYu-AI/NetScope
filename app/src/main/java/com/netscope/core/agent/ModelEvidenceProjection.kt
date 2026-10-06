package com.netscope.core.agent

import com.netscope.core.model.Evidence

/** Mask response credentials only in the model-bound copy; the collected Evidence stays intact. */
internal fun forModel(evidence: Evidence): Evidence = evidence.copy(
    rawSnapshot = SENSITIVE_HEADER.replace(evidence.rawSnapshot) { match ->
        "${match.groupValues[1]}: [REDACTED_FOR_MODEL]"
    },
)

private val SENSITIVE_HEADER = Regex(
    "(?im)^(Set-Cookie2?|Cookie|Authorization|Proxy-Authorization|Authentication-Info|Proxy-Authenticate|WWW-Authenticate|X-Api-Key):[^\\r\\n]*",
)
