package globalview

class GVMessagesCounter(var messagesToResolveConflict: Int = 0,
                        var messagesToCheckIfAlive: Int = 0,
                        var messagesBroadcast: Int = 0)