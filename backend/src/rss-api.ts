import { Post, PostsDatabase } from "./database";
import { PostError, PostHttpResponse, PostRequest, PostResponse, UnvalidatedPostRequest } from "./models";
import { URL } from "url";
import utils from "./utils";
import RSS from "rss";

const BASE_URL = (() => {
    const baseUrl = process.env.BASE_URL
    if (!baseUrl) {
        throw new Error('BASE_URL not set')
    }
    return baseUrl
})()

async function createPost(post: PostRequest): Promise<PostResponse | PostError> {
    try {
        const content = post.cleanupHtml
            ? utils.convertHtml(post.content)
            : post.content
        const row = await PostsDatabase.withConnection(async (db) => {
            const tags = post.content.match(/(?<=^|\s)#\w+/gm)?.map(t => t.substring(1)) || []
            return db.createPost({
                content,
                link: post.link,
                language: post.language,
                tags
            })
        })
        return {
            isSuccessful: true,
            uri: new URL(`/rss/${row.uuid}`, BASE_URL).toString()
        }
    } catch (e) {
        console.error(`[${new Date().toISOString()}] Failed to save RSS item: `, e)
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
    console.log(`[${new Date().toISOString()}] Saved RSS ITEM: ${r}`)
    if (!r.isSuccessful) {
        return r.status
            ? { status: r.status, body: ""+r.error }
            : { status: 500, body: "Internal Server Error (RSS)" }
    } else {
        return { status: 200, body: "OK" }
    }
}

async function rss(options: {
    filterByLinks?: "include" | "exclude",
}): Promise<string> {
    const posts = await PostsDatabase.withConnection(db => db.getPosts())
    const feed = new RSS({
        title: 'Feed of ' + BASE_URL.replace(/^https?:\/\//, ''),
        feed_url: new URL('/rss', BASE_URL).toString(),
        site_url: BASE_URL
    })
    for (const post of posts) {
        if (options.filterByLinks === "include" && !post.link) {
            continue
        } else if (options.filterByLinks === "exclude" && post.link) {
            continue
        }
        const guid = new URL(`/rss/${post.uuid}`, BASE_URL).toString()
        feed.item({
            title: post.content,
            description: post.content,
            categories: post.tags,
            guid,
            url: post.link || guid,
            date: post.createdAt
        })
    }
    return feed.xml({ indent: true })
}

async function getPost(uuid: string): Promise<Post | null> {
    return await PostsDatabase.withConnection(db => db.getPost(uuid))
}

export default {
    getPost,
    createPost,
    createPostRoute,
    rss
}
