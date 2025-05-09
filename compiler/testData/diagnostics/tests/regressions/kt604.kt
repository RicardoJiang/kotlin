// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// KT-604 Internal frontend error

interface ChannelPipeline {

}

class DefaultChannelPipeline() : ChannelPipeline {
}

interface ChannelPipelineFactory{
    fun getPipeline() : ChannelPipeline
}

class StandardPipelineFactory(val config:  ChannelPipeline.()->Unit) : ChannelPipelineFactory {
    override fun getPipeline() : ChannelPipeline {
        val pipeline = DefaultChannelPipeline()
        pipeline.config ()
        return pipeline
    }
}
