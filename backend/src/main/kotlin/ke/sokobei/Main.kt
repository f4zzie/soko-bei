package ke.sokobei

import io.ktor.server.netty.EngineMain
import ke.sokobei.demo.DemoCli

fun main(args: Array<String>) {
    if (args.firstOrNull()?.startsWith("demo-") == true) DemoCli.main(args)
    else EngineMain.main(args)
}
