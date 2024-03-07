export interface UnvalidatedPostRequest {
    content?: string,
    link?: string,
    language?: string,
    cleanupHtml?: boolean,
    images?: string[],
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

export interface Dictionary<T> {
    [key: string]: T;
}

