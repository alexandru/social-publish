import axios from 'axios'
import qs from 'qs'
import { PostError, PostHttpResponse, PostRequest, PostResponse, UnvalidatedPostRequest } from './models'
import utils from './utils'

const service = (() => {
    const service = process.env.MASTODON_HOST || 'https://mastodon.social'
    if (!service) throw new Error('MASTODON_HOST is not set')
    return service
})()

const accessToken = (() => {
    const token = process.env.MASTODON_ACCESS_TOKEN
    if (!token) throw new Error('MASTODON_ACCESS_TOKEN is not set')
    return token
})()

async function createPost(post: PostRequest): Promise<PostResponse | PostError> {
    try {
        const url = `${service}/api/v1/statuses`
        const status = post.cleanupHtml
            ? utils.convertHtml(post.content)
            : post.content.trim()
        const data = {
            status,
            language: post.language,
        }
        const response = await axios.post(
            url,
            qs.stringify(data),
            {
                headers: {
                    "Authorization": `Bearer ${accessToken}`,
                    "Content-Type": "application/x-www-form-urlencoded"
                },
            }
        )
        if (response.status >= 400) {
            console.error(`[${new Date().toISOString()}] Failed to post to Mastodon: `, response)
            return {
                isSuccessful: false,
                status: response.status,
                error: response.data["error"] || "HTTP " + response.status,
            }
        } else {
            return {
                isSuccessful: true,
                uri: response.data["url"],
            }
        }
    } catch (e) {
        console.error(`[${new Date().toISOString()}] Failed to post to Mastodon: `, e)
        return {
            isSuccessful: false,
            error: e
        }
    }
}

async function createPostRoute(post: UnvalidatedPostRequest): Promise<PostHttpResponse> {
    const content = post.content
    if (!content) {
        return { status: 400, body: "Bad Request: Missing content!" }
    }
    const r = await createPost({ ...post, content })
    console.log(`[${new Date().toISOString()}] Posted to Mastodon: ${r}`)
    if (!r.isSuccessful) {
        return r.status
            ? { status: r.status, body: ""+r.error }
            : { status: 500, body: "Internal Server Error (BlueSky)" }
    } else {
        return { status: 200, body: "OK" }
    }
}

export default {
    createPost,
    createPostRoute
}