export interface UnvalidatedPostRequest {
    content?: string,
    language?: string,
    cleanupHtml?: boolean,
}

export interface PostRequest extends UnvalidatedPostRequest {
    content: string
}

export interface PostError {
    isSuccessful: false,
    status?: number,
    error: unknown,
}

export interface PostResponse {
    isSuccessful: true,
    uri: string,
    cid?: string,
}

export interface PostHttpResponse {
    status: number,
    body: string
}
