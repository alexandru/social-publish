import { BskyAgent, RichText } from '@atproto/api'

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

async function createPost(post: {
    content: string,
    langs?: string[],
    createdAt?: Date,
}): Promise<{ uri: string; cid: string }> {
    const agent = await getOrInitAgent()
    const rt = new RichText({ text: post.content })
    await rt.detectFacets(agent)

    return await agent.post({
        text: rt.text,
        facets: rt.facets,
        createdAt: (post.createdAt || new Date()).toISOString(),
        langs: post.langs || ['en-US'],
    })
}

export default {
    createPost
}