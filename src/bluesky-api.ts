import { BskyAgent, RichText } from '@atproto/api'
import utils from './utils'
import { PostError, PostHttpResponse, PostRequest, PostResponse, UnvalidatedPostRequest } from './models'

const service = process.env.BSKY_HOST || 'https://bsky.social'

const identifier = (() => {
    const id = process.env.BSKY_USERNAME
    if (!id) throw new Error('BSKY_USERNAME is not set')
    return id
})()

const password = (() => {
    const pw = process.env.BSKY_PASSWORD
    if (!pw) throw new Error('BSKY_PASSWORD is not set')
    return pw
})()

let _agent: BskyAgent | null = null
async function getOrInitAgent(): Promise<BskyAgent> {
    if (_agent === null) {
        const ref = new BskyAgent({ service })
        const r = await ref.login({
            identifier,
            password
        })
        if (!r) {
            throw new Error('Failed to login to BlueSky')
        }
        _agent = ref
    }
    return _agent
}

getOrInitAgent().catch(e => {
    console.error('Failed to login to BlueSky:', e)
})

async function createPost(post: PostRequest): Promise<PostResponse | PostError> {
    try {
        const text = post.cleanupHtml ? utils.convertHtml(post.content) : post.content.trim();
        console.log(
            `[${new Date().toISOString()}] Posting to BlueSky:\n${text.trim().replace(/^/gm, '  |')}`,
        )
        const agent = await getOrInitAgent()
        const rt = new RichText({ text })
        await rt.detectFacets(agent)
        const r = await agent.post({
            text: rt.text,
            facets: rt.facets,
            createdAt: new Date().toISOString(),
            langs: post.language ? [post.language] : undefined,
        })
        return { isSuccessful: true, ...r }
    } catch (e) {
        console.error(
            `[${new Date().toISOString()}] Failed to post to BlueSky:`,
            e
        )
        const eany = e as any
        if (eany.status && eany.error)
            return {
                isSuccessful: false,
                status: eany.status,
                error: eany.error
            }
        else
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
    createPostRoute,
}
